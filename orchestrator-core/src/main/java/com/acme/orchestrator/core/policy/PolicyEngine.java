package com.acme.orchestrator.core.policy;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Executor;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.policy.ProposedAction.Command;
import com.acme.orchestrator.core.policy.ProposedAction.Diff;
import com.acme.orchestrator.core.policy.ProposedAction.FileWrite;
import com.acme.orchestrator.core.policy.ProposedAction.Publish;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a proposed action may happen: ALLOW, REQUIRE_APPROVAL or DENY.
 *
 * <ul>
 *   <li>Every policy that applies is reported; the effect is the strictest of them (any DENY →
 *       DENY, else any REQUIRE_APPROVAL → REQUIRE_APPROVAL), so nothing can lower a DENY.
 *   <li>The autonomy level only tightens: L0 needs approval for every write. Its single relaxation
 *       is stage-level: at L2 the design approval is skipped when the risk score is below 30.
 *   <li>Publishing always needs human approval, whatever the config says ({@link Invariant}).
 *   <li>Risk score: the weights of the matched config policies plus a size/impact factor (diff: up
 *       to 30 by lines and files; file write: up to 10 by lines; publish: 30), capped at 100.
 * </ul>
 *
 * A diff is checked against the {@code file_write} path rules too, for every path it touches, so
 * the final change set can't contain a write a per-file check would have refused.
 *
 * <p>The engine is immutable: it holds the {@link PolicySet} loaded at run start.
 */
public final class PolicyEngine {

  /** The stage whose approval L2 may skip. */
  public static final String DESIGN_STAGE = "design";

  /** Actions that publish (always need human approval). */
  public static final Set<String> PUBLISH_ACTIONS = Set.of("git.mergeToMain");

  public static final int L2_DESIGN_SKIP_BELOW = 30;

  static final String INVALID_PATH = "SYS-001-invalid-path";
  static final String L0_WRITES = "AUTONOMY-L0-writes";
  static final String PUBLISH_APPROVAL = "INV-publish-approval";
  static final int MAX_DETAILS = 5;

  private static final Pattern WINDOWS_DRIVE = Pattern.compile("[A-Za-z]:.*");

  private final PolicySet policies;

  private PolicyEngine(PolicySet policies) {
    this.policies = Objects.requireNonNull(policies, "policies");
  }

  public static PolicyEngine of(PolicySet policies) {
    return new PolicyEngine(policies);
  }

  /** Loads {@code policies.yml} and {@code autonomy.yml} from {@code configDir}. */
  public static PolicyEngine load(Path configDir) {
    return of(new PolicyLoader().load(configDir));
  }

  /** No config policies; the invariants (publish needs approval, …) still apply. */
  public static PolicyEngine withoutPolicies() {
    return of(PolicySet.EMPTY);
  }

  public PolicySet policies() {
    return policies;
  }

  public Decision evaluate(ProposedAction action, AutonomyLevel level) {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(level, "level");
    Matches matches = new Matches();
    int impact =
        switch (action) {
          case FileWrite write -> {
            normalize(write.path())
                .ifPresentOrElse(
                    path -> checkPath(path, matches), () -> matches.invalidPath(write.path()));
            yield Math.min(10, (int) write.content().lines().count() / 100);
          }
          case Diff diff -> {
            UnifiedDiff parsed = UnifiedDiff.parse(diff.unifiedDiff());
            for (String raw : parsed.paths()) {
              normalize(raw)
                  .ifPresentOrElse(
                      path -> checkPath(path, matches), () -> matches.invalidPath(raw));
            }
            checkContent(parsed, matches);
            int files = Math.max(diff.filesChanged(), parsed.paths().size());
            int lines = Math.max(diff.linesChanged(), parsed.added().size());
            checkSize(files, lines, matches);
            yield Math.min(30, lines / 40 + files);
          }
          case Command command -> {
            checkCommand(command.cmdline().strip(), matches);
            yield 0;
          }
          case Publish publish -> {
            matches.add(
                PUBLISH_APPROVAL,
                Effect.REQUIRE_APPROVAL,
                0,
                Invariant.PUBLISH_REQUIRES_APPROVAL.text(),
                null);
            yield 30;
          }
        };
    if (level == AutonomyLevel.L0_SUGGEST
        && (action instanceof FileWrite || action instanceof Diff)) {
      matches.add(
          L0_WRITES, Effect.REQUIRE_APPROVAL, 0, "every write needs approval at L0_SUGGEST", null);
    }
    return matches.decision(impact);
  }

  /**
   * Whether a stage waits for a human after its exit gates pass. The workflow's {@code approval}
   * decides, tightened by the invariant (a publish stage, i.e. one running a {@link
   * #PUBLISH_ACTIONS} action, always waits); the one relaxation: at L2 the design stage skips
   * approval when its risk is known to be below {@value #L2_DESIGN_SKIP_BELOW}.
   *
   * @param riskScore the stage's assessed risk; empty when unknown (no relaxation applies)
   */
  public boolean stageApprovalRequired(
      StageDefinition stage, AutonomyLevel level, OptionalInt riskScore) {
    if (stage.executor() instanceof Executor.Action action
        && PUBLISH_ACTIONS.contains(action.name())) {
      return true;
    }
    if (!stage.approval().required()) {
      return false;
    }
    boolean l2LowRiskDesign =
        level == AutonomyLevel.L2_TRUSTED
            && stage.id().equals(DESIGN_STAGE)
            && riskScore.isPresent()
            && riskScore.getAsInt() < L2_DESIGN_SKIP_BELOW;
    return !l2LowRiskDesign;
  }

  // ---- checks ---------------------------------------------------------------------------------

  private void checkPath(String path, Matches matches) {
    for (Policy policy : policies.policies()) {
      switch (policy) {
        case Policy.PathJail jail when !jail.allows(path) ->
            matches.add(jail, "write outside the allowed paths", path);
        case Policy.PathRule rule ->
            rule.match(path)
                .ifPresent(glob ->
                    matches.add(rule, "write to a protected path", path + " (" + glob + ")"));
        default -> {}
      }
    }
  }

  private void checkContent(UnifiedDiff diff, Matches matches) {
    for (Policy policy : policies.policies()) {
      if (policy instanceof Policy.DenyPatterns rule) {
        for (UnifiedDiff.AddedLine line : diff.added()) {
          for (int p = 0; p < rule.patterns().size(); p++) {
            if (rule.patterns().get(p).matcher(line.text()).find()) {
              // never quote the line: it may hold the secret
              matches.add(
                  rule,
                  "an added line matches a forbidden pattern",
                  line.path() + ":" + line.line() + " (pattern " + (p + 1) + ")");
            }
          }
        }
      }
    }
  }

  private void checkSize(int files, int lines, Matches matches) {
    for (Policy policy : policies.policies()) {
      if (policy instanceof Policy.SizeLimit limit) {
        List<String> exceeded = new ArrayList<>();
        limit
            .maxFiles()
            .filter(max -> files > max)
            .ifPresent(max -> exceeded.add(files + " files (max " + max + ")"));
        limit
            .maxLines()
            .filter(max -> lines > max)
            .ifPresent(max -> exceeded.add(lines + " lines (max " + max + ")"));
        if (!exceeded.isEmpty()) {
          matches.add(limit, "the change is too large", String.join(", ", exceeded));
        }
      }
    }
  }

  private void checkCommand(String cmdline, Matches matches) {
    for (Policy policy : policies.policies()) {
      if (policy instanceof Policy.CommandAllowlist list && !list.allow().contains(cmdline)) {
        String shown = cmdline.length() > 200 ? cmdline.substring(0, 200) + "…" : cmdline;
        matches.add(list, "the command is not on the allowlist", "'" + shown + "'");
      }
    }
  }

  /**
   * A repository-relative path with {@code /} separators, or empty if it is absolute, escapes with
   * {@code ..}, or is otherwise not a plain relative path. (Symlinks are FileTool's job.)
   */
  static Optional<String> normalize(String raw) {
    String path = raw.replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    if (path.isEmpty()
        || path.startsWith("/")
        || WINDOWS_DRIVE.matcher(path).matches()
        || path.endsWith("/")) {
      return Optional.empty();
    }
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return Optional.empty();
      }
    }
    return Optional.of(path);
  }

  /** Collects matches, one per policy, each listing up to {@value #MAX_DETAILS} occurrences. */
  private static final class Matches {
    private record Entry(
        String id, Effect effect, int weight, String reason, List<String> details) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    void add(Policy policy, String defaultReason, String detail) {
      add(
          policy.id(),
          policy.effect(),
          policy.weight(),
          policy.message().orElse(defaultReason),
          detail);
    }

    void add(String id, Effect effect, int weight, String reason, String detail) {
      Entry entry =
          entries.computeIfAbsent(
              id, k -> new Entry(id, effect, weight, reason, new ArrayList<>()));
      if (detail != null) {
        entry.details().add(detail);
      }
    }

    void invalidPath(String raw) {
      add(
          INVALID_PATH,
          Effect.DENY,
          Policy.DEFAULT_DENY_WEIGHT,
          "paths must be relative to the repository, without '..'",
          "'" + raw + "'");
    }

    Decision decision(int impact) {
      Effect effect = Effect.ALLOW;
      int risk = impact;
      List<PolicyMatch> matched = new ArrayList<>();
      for (Entry e : entries.values()) {
        effect = effect.strictest(e.effect());
        risk += e.weight();
        matched.add(new PolicyMatch(e.id(), e.effect(), reason(e)));
      }
      return new Decision(effect, matched, Math.min(100, risk));
    }

    private static String reason(Entry e) {
      if (e.details().isEmpty()) {
        return e.reason();
      }
      List<String> shown = e.details().subList(0, Math.min(MAX_DETAILS, e.details().size()));
      String more =
          e.details().size() > MAX_DETAILS
              ? ", … and " + (e.details().size() - MAX_DETAILS) + " more"
              : "";
      String reason =
          e.reason().endsWith(".") ? e.reason().substring(0, e.reason().length() - 1) : e.reason();
      return reason + ": " + String.join(", ", shown) + more;
    }
  }
}
