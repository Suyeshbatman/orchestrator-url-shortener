package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.Authorizer;
import com.acme.orchestrator.agents.tools.CommandTool;
import com.acme.orchestrator.agents.tools.DiffTool;
import com.acme.orchestrator.agents.tools.DockerSandbox;
import com.acme.orchestrator.agents.tools.Sandbox;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.ArtifactOutput;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.Effect;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.PolicyMatch;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic, no LLM: builds and tests the workspace with the allowlisted Maven command, reads
 * the Surefire/Failsafe and JaCoCo reports, puts the whole diff to the policy engine, and writes
 * validation_report for the build, tests, coverage and policy gates. Gate failures loop back to the
 * implementation with this report as feedback.
 *
 * <p>The diff is judged here rather than when it was written, so a DENY (a secret, a logged client
 * IP) becomes a fixable validation failure instead of a dead end; an ALLOW or REQUIRE_APPROVAL
 * verdict is also recorded through the stage (a big change then waits for a human).
 */
public final class ValidationAgent implements Agent {

  public static final String NAME = "ValidationAgent";

  /**
   * @param command must be on CMD-001's allowlist
   * @param module the Maven module whose reports are read (relative to the workspace)
   * @param coverageExcludes packages the coverage figure leaves out (like the module's own gate)
   * @param sandboxing where the build runs (the build runs the code under test)
   */
  public record Settings(
      String command,
      String module,
      Set<String> coverageExcludes,
      Duration timeout,
      Sandboxing sandboxing) {

    public static final Settings SHORTENER =
        new Settings(
            "mvn -q -pl shortener-service -am verify",
            "shortener-service",
            Set.of("com.acme.shortener.config"),
            CommandTool.DEFAULT_TIMEOUT,
            Sandboxing.AUTO);

    public Settings {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(module, "module");
      coverageExcludes = Set.copyOf(coverageExcludes);
      Objects.requireNonNull(timeout, "timeout");
      Objects.requireNonNull(sandboxing, "sandboxing");
    }

    public Settings(String command, String module, Set<String> coverageExcludes, Duration timeout) {
      this(command, module, coverageExcludes, timeout, Sandboxing.AUTO);
    }

    public Settings withSandboxing(Sandboxing sandboxing) {
      return new Settings(command, module, coverageExcludes, timeout, sandboxing);
    }
  }

  /**
   * Where the validation build runs. {@code AUTO}: in the Docker sandbox when the code is
   * unreviewed model output (live and record modes), on this machine otherwise (replayed fixtures
   * and hand-written replies were reviewed when they were committed). Configured with {@code
   * ORCH_VALIDATION_SANDBOX=auto|docker|none}.
   */
  public enum Sandboxing {
    NONE,
    AUTO,
    DOCKER;

    public static Sandboxing parse(String value) {
      return value == null || value.isBlank()
          ? AUTO
          : Sandboxing.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
  }

  static final int SUMMARY_LINES = 40;

  /** Test timings ("Time elapsed: 7.6 s"): they differ on every run. */
  private static final Pattern ELAPSED = Pattern.compile(",? *(-- )?Time elapsed: [0-9.,]+ s");

  /** Maven's boilerplate after the real errors. */
  private static final Pattern MAVEN_HINT =
      Pattern.compile(
          "\\[ERROR\\]\\s*(-> \\[Help.*|Re-run Maven.*|To see the full.*|For more.*|\\[Help.*)?");

  private final PolicyEngine policies;
  private final Settings settings;
  private final Optional<Sandbox> sandbox;

  public ValidationAgent(PolicyEngine policies, Settings settings) {
    this(policies, settings, false, DockerSandbox::new);
  }

  /**
   * @param unreviewedCode the code comes from a live model no human has reviewed
   * @param sandboxes makes the sandbox when the settings call for one
   */
  public ValidationAgent(
      PolicyEngine policies,
      Settings settings,
      boolean unreviewedCode,
      Supplier<? extends Sandbox> sandboxes) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.settings = Objects.requireNonNull(settings, "settings");
    boolean sandboxed =
        settings.sandboxing() == Sandboxing.DOCKER
            || (settings.sandboxing() == Sandboxing.AUTO && unreviewedCode);
    this.sandbox = sandboxed ? Optional.of(sandboxes.get()) : Optional.empty();
  }

  /** Whether this agent's builds run in the sandbox. */
  public boolean sandboxed() {
    return sandbox.isPresent();
  }

  @Override
  public AgentResult execute(StageContext ctx) {
    Workspace workspace = ctx.tools().require(Workspace.class);
    Path module = workspace.root().resolve(settings.module()).normalize();
    clearReports(workspace, module); // never judge this attempt by an earlier build's reports

    CommandTool.Result build =
        new CommandTool(workspace, Authorizer.of(ctx), settings.timeout(), sandbox)
            .run(settings.command());
    BuildReports.Tests tests = BuildReports.tests(module);
    double coverage = BuildReports.lineCoverage(module, settings.coverageExcludes());
    DiffTool.Result diff = new DiffTool(workspace).diff();
    Decision decision = policies.evaluate(diff.toAction(), ctx.run().autonomyLevel());
    if (decision.effect() != Effect.DENY) {
      ctx.authorize(diff.toAction()); // audited; REQUIRE_APPROVAL makes the stage wait for a human
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put(
        "build",
        Map.of(
            "status", build.succeeded() ? "SUCCESS" : "FAILURE",
            "command", settings.command(),
            "summary", summary(build, workspace.root())));
    report.put(
        "tests",
        Map.of(
            "total", tests.total(),
            "failed", tests.failed(),
            "errors", tests.errors(),
            "skipped", tests.skipped(),
            "failures",
                tests.failures().stream()
                    .map(f -> Map.of("test", f.test(), "message", f.message()))
                    .toList()));
    report.put(
        "coverage",
        // to the percent: background threads make the last digits vary between runs, and the report
        // feeds later prompts (and their replay hashes); the build itself enforces the exact gate
        Map.of("line", BigDecimal.valueOf(coverage).setScale(2, RoundingMode.HALF_UP)));
    report.put(
        "policy",
        Map.of(
            "decision", decision.effect().name(),
            "riskScore", decision.riskScore(),
            "matchedPolicies",
                decision.matchedPolicies().stream().map(ValidationAgent::match).toList()));
    report.put(
        "diff",
        Map.of(
            "filesChanged", diff.filesChanged(),
            "linesChanged", diff.linesChanged(),
            "paths", diff.paths()));

    String line =
        String.format(
            Locale.ROOT,
            "build %s%s, %d tests (%d failed, %d errors), line coverage %.2f, policy %s",
            build.succeeded() ? "SUCCESS" : (build.timedOut() ? "TIMED OUT" : "FAILURE"),
            sandbox.isPresent() ? " (sandboxed)" : "",
            tests.total(),
            tests.failed(),
            tests.errors(),
            coverage,
            decision.effect());
    return AgentResult.success(
        line,
        ArtifactOutput.of(
            ArtifactTypes.VALIDATION_REPORT,
            CanonicalJson.write(
                report), // stable key order: later prompts (and their replay hashes) include it
            "measured by running '" + settings.command() + "' in the run's workspace"));
  }

  private static Map<String, Object> match(PolicyMatch m) {
    return Map.of("id", m.policyId(), "decision", m.effect().name(), "reason", m.reason());
  }

  /**
   * What the build says went wrong: Maven's {@code [ERROR]} lines, with the workspace path removed
   * (or the end of the log when there are none). Nothing on success. The report feeds later
   * prompts, so it holds nothing that changes from run to run (timestamps, PIDs, paths), or
   * recorded replies would never match again.
   */
  static String summary(CommandTool.Result build, Path workspace) {
    if (build.timedOut()) {
      return "timed out after " + build.duration().toSeconds() + " s";
    }
    if (build.succeeded()) {
      return "build succeeded";
    }
    String root = workspace.toString();
    List<String> lines =
        build
            .output()
            .strip()
            .lines()
            .map(l -> ELAPSED.matcher(l.replace(root + File.separator, "")).replaceAll(""))
            .toList();
    List<String> errors =
        lines.stream()
            .filter(l -> l.startsWith("[ERROR]"))
            .filter(l -> !MAVEN_HINT.matcher(l).matches())
            .toList();
    List<String> shown = errors.isEmpty() ? lines : errors;
    List<String> tail = shown.subList(Math.max(0, shown.size() - SUMMARY_LINES), shown.size());
    String text = String.join("\n", tail);
    return text.isBlank() ? "exit code " + build.exitCode() : text;
  }

  private static void clearReports(Workspace workspace, Path module) {
    if (!module.startsWith(workspace.root())) {
      throw new IllegalArgumentException("the module must be inside the workspace: " + module);
    }
    for (String dir :
        List.of(
            BuildReports.REPORT_DIRS.get(0),
            BuildReports.REPORT_DIRS.get(1),
            "target/site/jacoco")) {
      Path path = module.resolve(dir);
      if (!Files.exists(path)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(path)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("cannot clear old reports in " + path, e);
      }
    }
  }
}
