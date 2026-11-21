package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.Authorizer;
import com.acme.orchestrator.agents.tools.CommandTool;
import com.acme.orchestrator.agents.tools.DiffTool;
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
import java.util.Set;
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
   */
  public record Settings(
      String command, String module, Set<String> coverageExcludes, Duration timeout) {

    public static final Settings SHORTENER =
        new Settings(
            "mvn -q -pl shortener-service -am verify",
            "shortener-service",
            Set.of("com.acme.shortener.config"),
            CommandTool.DEFAULT_TIMEOUT);

    public Settings {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(module, "module");
      coverageExcludes = Set.copyOf(coverageExcludes);
      Objects.requireNonNull(timeout, "timeout");
    }
  }

  static final int SUMMARY_LINES = 40;

  /** Maven's boilerplate after the real errors. */
  private static final Pattern MAVEN_HINT =
      Pattern.compile(
          "\\[ERROR\\]\\s*(-> \\[Help.*|Re-run Maven.*|To see the full.*|For more.*|\\[Help.*)?");

  private final PolicyEngine policies;
  private final Settings settings;

  public ValidationAgent(PolicyEngine policies, Settings settings) {
    this.policies = Objects.requireNonNull(policies, "policies");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public AgentResult execute(StageContext ctx) {
    Workspace workspace = ctx.tools().require(Workspace.class);
    Path module = workspace.root().resolve(settings.module()).normalize();
    clearReports(workspace, module); // never judge this attempt by an earlier build's reports

    CommandTool.Result build =
        new CommandTool(workspace, Authorizer.of(ctx), settings.timeout()).run(settings.command());
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
        "coverage", Map.of("line", BigDecimal.valueOf(coverage).setScale(4, RoundingMode.HALF_UP)));
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
            "build %s, %d tests (%d failed, %d errors), line coverage %.2f, policy %s",
            build.succeeded() ? "SUCCESS" : (build.timedOut() ? "TIMED OUT" : "FAILURE"),
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
        build.output().strip().lines().map(l -> l.replace(root + File.separator, "")).toList();
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
