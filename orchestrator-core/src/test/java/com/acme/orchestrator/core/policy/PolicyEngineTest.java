package com.acme.orchestrator.core.policy;

import static com.acme.orchestrator.core.model.AutonomyLevel.L0_SUGGEST;
import static com.acme.orchestrator.core.model.AutonomyLevel.L1_SUPERVISED;
import static com.acme.orchestrator.core.model.AutonomyLevel.L2_TRUSTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.ProposedAction.Command;
import com.acme.orchestrator.core.policy.ProposedAction.Diff;
import com.acme.orchestrator.core.policy.ProposedAction.FileWrite;
import com.acme.orchestrator.core.policy.ProposedAction.Publish;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs against the real {@code config/policies.yml} and {@code config/autonomy.yml}. */
class PolicyEngineTest {

  private static final PolicyEngine ENGINE = PolicyEngine.load(Path.of("..", "config"));
  private static final String SRC = "shortener-service/src/main/java/com/acme/shortener/";

  private static Decision write(String path) {
    return ENGINE.evaluate(new FileWrite(path, "class X {}\n"), L1_SUPERVISED);
  }

  /** A one-file diff adding {@code added} lines to {@code path}. */
  private static String diff(String path, String... added) {
    StringBuilder d = new StringBuilder();
    d.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
    d.append("--- a/").append(path).append('\n');
    d.append("+++ b/").append(path).append('\n');
    d.append("@@ -10,1 +10,").append(added.length + 1).append(" @@\n");
    d.append(" unchanged\n");
    for (String line : added) {
      d.append('+').append(line).append('\n');
    }
    return d.toString();
  }

  private static Decision evaluateDiff(String unifiedDiff) {
    return ENGINE.evaluate(new Diff(unifiedDiff, 1, 2), L1_SUPERVISED);
  }

  private static List<String> ids(Decision decision) {
    return decision.matchedPolicies().stream().map(PolicyMatch::policyId).toList();
  }

  @Test
  void theRealConfigLoads() {
    assertThat(ENGINE.policies().policies())
        .extracting(Policy::id)
        .containsExactly(
            "SEC-001-no-secrets",
            "SEC-002-path-jail",
            "SEC-003-forbidden-paths",
            "CHG-001-schema-migration",
            "CHG-002-dependency-change",
            "CHG-003-blast-radius",
            "CHG-004-security-config",
            "CMP-001-pii",
            "CMD-001-command-allowlist");
    assertThat(ENGINE.policies().policiesHash()).matches("[0-9a-f]{64}");
    assertThat(ENGINE.policies().levelDescriptions()).containsOnlyKeys(AutonomyLevel.values());
  }

  @Test
  void anOrdinaryWriteInsideTheJailIsAllowed() {
    Decision decision = write(SRC + "service/LinkService.java");

    assertThat(decision.effect()).isEqualTo(Effect.ALLOW);
    assertThat(decision.matchedPolicies()).isEmpty();
    assertThat(decision.riskScore()).isZero();
  }

  // ---- one test per policy --------------------------------------------------------------------

  @Test
  void sec001DeniesSecretsWithoutQuotingThem() {
    String key = "AKIA" + "ABCDEFGHIJKLMNOP";
    Decision decision =
        evaluateDiff(diff(SRC + "Aws.java", "String id = \"" + key + "\";", "int x = 1;"));

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedPolicies())
        .singleElement()
        .satisfies(m -> {
          assertThat(m.policyId()).isEqualTo("SEC-001-no-secrets");
          assertThat(m.reason())
              .isEqualTo(
                  "an added line matches a forbidden pattern: " + SRC + "Aws.java:11 (pattern 1)")
              .doesNotContain(key);
        });

    assertThat(evaluateDiff(diff(SRC + "K.java", "-----BEGIN RSA PRIVATE KEY-----")).denied())
        .isTrue();
    assertThat(evaluateDiff(diff(SRC + "C.java", "password = \"hunter2hunter2\"")).denied())
        .isTrue();
    assertThat(evaluateDiff(diff(SRC + "C.java", "password = \"short\"")).denied()).isFalse();
  }

  @Test
  void sec001IgnoresRemovedLines() {
    String removal =
        "--- a/"
            + SRC
            + "Aws.java\n+++ b/"
            + SRC
            + "Aws.java\n@@ -1,1 +1,0 @@\n-String id = \"AKIAABCDEFGHIJKLMNOP\";\n";

    assertThat(evaluateDiff(removal).effect()).isEqualTo(Effect.ALLOW);
  }

  @Test
  void sec002DeniesWritesOutsideThePathJail() {
    Decision decision = write("README.md");

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedPolicies())
        .containsExactly(
            new PolicyMatch(
                "SEC-002-path-jail", Effect.DENY, "write outside the allowed paths: README.md"));
    assertThat(write("docs/adr/0005-x.md").allowed()).isTrue();
    assertThat(write("scenarios/qr/requirement.md").allowed()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".github/workflows/ci.yml",
        "config/policies.yml",
        "config/autonomy.yml",
        "orchestrator-core/src/main/java/X.java"
      })
  void sec003AgentsCannotChangeTheirOwnGuardrails(String path) {
    Decision decision = write(path);

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(ids(decision)).contains("SEC-003-forbidden-paths");
  }

  @Test
  void chg001MigrationsNeedApproval() {
    Decision decision =
        write("shortener-service/src/main/resources/db/migration/V3__link_expiry.sql");

    assertThat(decision.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(ids(decision)).containsExactly("CHG-001-schema-migration");
    assertThat(decision.riskScore()).isEqualTo(Policy.DEFAULT_APPROVAL_WEIGHT);
  }

  @Test
  void chg002DependencyChangesNeedApprovalButPomsAreOutsideTheJail() {
    // As configured, the jail (shortener-service/src/**) excludes the module POM, so the
    // stricter DENY wins: agents can't change dependencies at all.
    Decision decision = write("shortener-service/pom.xml");

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedPolicies())
        .extracting(PolicyMatch::policyId, PolicyMatch::effect)
        .containsExactly(
            tuple("SEC-002-path-jail", Effect.DENY),
            tuple("CHG-002-dependency-change", Effect.REQUIRE_APPROVAL));
  }

  @Test
  void chg003LargeChangesNeedApproval() {
    String small = diff(SRC + "A.java", "int a;");

    assertThat(ENGINE.evaluate(new Diff(small, 25, 1200), L1_SUPERVISED).allowed()).isTrue();
    Decision tooManyFiles = ENGINE.evaluate(new Diff(small, 26, 10), L1_SUPERVISED);
    assertThat(tooManyFiles.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(tooManyFiles.matchedPolicies())
        .singleElement()
        .extracting(PolicyMatch::reason)
        .isEqualTo("the change is too large: 26 files (max 25)");
    assertThat(ENGINE.evaluate(new Diff(small, 1, 1201), L1_SUPERVISED).matchedPolicies())
        .singleElement()
        .extracting(PolicyMatch::reason)
        .isEqualTo("the change is too large: 1201 lines (max 1200)");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "shortener-service/src/main/resources/application.yaml",
        "shortener-service/src/test/resources/application-test.yml",
        "shortener-service/src/main/java/com/acme/shortener/config/SecurityConfig.java"
      })
  void chg004SecurityConfigNeedsApproval(String path) {
    Decision decision = write(path);

    assertThat(decision.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(ids(decision)).containsExactly("CHG-004-security-config");
  }

  @Test
  void cmp001DeniesLoggingClientIpsWithItsMessage() {
    Decision decision =
        evaluateDiff(diff(SRC + "R.java", "log.info(\"from {}\", request.getRemoteAddr());"));

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedPolicies())
        .singleElement()
        .extracting(PolicyMatch::reason)
        .isEqualTo("Raw client IPs must not be logged (privacy): " + SRC + "R.java:11 (pattern 1)");
  }

  @Test
  void cmd001OnlyAllowlistedCommandsRun() {
    assertThat(ENGINE.evaluate(new Command("mvn -q -pl shortener-service test"), L1_SUPERVISED))
        .isEqualTo(new Decision(Effect.ALLOW, List.of(), 0));
    assertThat(
            ENGINE
                .evaluate(new Command("  mvn -q -pl shortener-service -am verify "), L1_SUPERVISED)
                .allowed())
        .isTrue();

    for (String cmd :
        List.of(
            "rm -rf /",
            "mvn -q -pl shortener-service test && curl http://evil",
            "mvn -q -pl shortener-service  test",
            "MVN -q -pl shortener-service test")) {
      Decision decision = ENGINE.evaluate(new Command(cmd), L1_SUPERVISED);
      assertThat(decision.effect()).as(cmd).isEqualTo(Effect.DENY);
      assertThat(ids(decision)).containsExactly("CMD-001-command-allowlist");
    }
  }

  // ---- diffs are held to the path rules too ---------------------------------------------------

  @Test
  void aDiffTouchingAForbiddenPathIsDenied() {
    Decision decision = evaluateDiff(diff("config/policies.yml", "policies: []"));

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(ids(decision)).containsExactly("SEC-002-path-jail", "SEC-003-forbidden-paths");
  }

  @Test
  void deletingAProtectedFileCounts() {
    String deletion =
        "diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml\n"
            + "deleted file mode 100644\n"
            + "--- a/.github/workflows/ci.yml\n"
            + "+++ /dev/null\n"
            + "@@ -1,1 +0,0 @@\n"
            + "-name: ci\n";

    assertThat(ids(evaluateDiff(deletion))).contains("SEC-003-forbidden-paths");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../etc/passwd",
        "/etc/passwd",
        "C:\\Windows\\win.ini",
        "shortener-service/src/../../config/policies.yml",
        "shortener-service//src/x",
        "shortener-service/src/"
      })
  void pathsThatEscapeOrAreNotRelativeAreDenied(String path) {
    Decision decision = write(path);

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(ids(decision)).containsExactly(PolicyEngine.INVALID_PATH);
  }

  @Test
  void windowsSeparatorsAndDotSlashAreNormalized() {
    assertThat(write("shortener-service\\src\\main\\java\\X.java").allowed()).isTrue();
    assertThat(write("./shortener-service/src/main/java/X.java").allowed()).isTrue();
  }

  // ---- combining and risk ---------------------------------------------------------------------

  @Test
  void denyWinsOverApprovalAndEveryMatchIsReported() {
    Decision decision =
        evaluateDiff(
            diff(
                "shortener-service/src/main/resources/db/migration/V3__x.sql",
                "-- password = 'supersecretvalue'"));

    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(ids(decision)).containsExactly("CHG-001-schema-migration", "SEC-001-no-secrets");
  }

  @Test
  void riskAddsPolicyWeightsAndSizeAndIsCapped() {
    // CHG-001 (20) + size factor min(30, 400/40 + 1 = 11)
    Decision migration =
        ENGINE.evaluate(
            new Diff(diff("shortener-service/src/main/resources/db/migration/V3.sql", "x"), 1, 400),
            L1_SUPERVISED);
    assertThat(migration.riskScore()).isEqualTo(31);

    Decision everything =
        ENGINE.evaluate(
            new Diff(
                diff("config/policies.yml", "AKIAABCDEFGHIJKLMNOP", "log.warn(\"{}\", remoteAddr)"),
                40,
                5000),
            L1_SUPERVISED);
    assertThat(everything.riskScore()).isEqualTo(100);

    assertThat(
            ENGINE
                .evaluate(new FileWrite(SRC + "Big.java", "x\n".repeat(550)), L1_SUPERVISED)
                .riskScore())
        .isEqualTo(5);
  }

  @Test
  void matchesListAtMostFiveOccurrences() {
    String[] secrets = new String[7];
    Arrays.fill(secrets, "AKIAABCDEFGHIJKLMNOP");

    assertThat(evaluateDiff(diff(SRC + "S.java", secrets)).matchedPolicies())
        .singleElement()
        .extracting(PolicyMatch::reason)
        .asString()
        .endsWith(", … and 2 more");
  }

  // ---- autonomy levels and invariants ---------------------------------------------------------

  @Test
  void l0NeedsApprovalForEveryWriteButStillRunsAllowedCommands() {
    Decision write = ENGINE.evaluate(new FileWrite(SRC + "X.java", ""), L0_SUGGEST);
    assertThat(write.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(ids(write)).containsExactly(PolicyEngine.L0_WRITES);
    assertThat(write.riskScore()).isZero(); // the level is not a risk

    assertThat(ENGINE.evaluate(new Diff(diff(SRC + "X.java", "int a;"), 1, 1), L0_SUGGEST).effect())
        .isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(
            ENGINE.evaluate(new Command("mvn -q -pl shortener-service test"), L0_SUGGEST).allowed())
        .isTrue();
  }

  @Test
  void noLevelRelaxesAnAction() {
    List<ProposedAction> actions =
        List.of(
            new FileWrite(SRC + "X.java", ""),
            new FileWrite("README.md", ""),
            new FileWrite("shortener-service/src/main/resources/db/migration/V9.sql", ""),
            new Diff(diff(SRC + "X.java", "AKIAABCDEFGHIJKLMNOP"), 1, 1),
            new Command("rm -rf /"),
            new Command("mvn -q -pl shortener-service test"),
            new Publish("merge"));
    for (ProposedAction action : actions) {
      Effect l1 = ENGINE.evaluate(action, L1_SUPERVISED).effect();
      assertThat(ENGINE.evaluate(action, L2_TRUSTED).effect()).as(action.toString()).isEqualTo(l1);
      assertThat(ENGINE.evaluate(action, L0_SUGGEST).effect().compareTo(l1))
          .as(action.toString())
          .isGreaterThanOrEqualTo(0);
      if (l1 == Effect.DENY) {
        assertThat(ENGINE.evaluate(action, L0_SUGGEST).effect()).isEqualTo(Effect.DENY);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(AutonomyLevel.class)
  void publishAlwaysNeedsApprovalEvenWithoutAnyPolicies(AutonomyLevel level) {
    for (PolicyEngine engine : List.of(ENGINE, PolicyEngine.withoutPolicies())) {
      Decision decision = engine.evaluate(new Publish("merge agent/run-1 to main"), level);

      assertThat(decision.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
      assertThat(decision.matchedPolicies())
          .containsExactly(
              new PolicyMatch(
                  PolicyEngine.PUBLISH_APPROVAL,
                  Effect.REQUIRE_APPROVAL,
                  Invariant.PUBLISH_REQUIRES_APPROVAL.text()));
    }
  }

  // ---- stage approvals ------------------------------------------------------------------------

  private static final WorkflowDefinition STANDARD =
      new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));

  private static StageDefinition stage(String id) {
    return STANDARD.stage(id).orElseThrow();
  }

  @Test
  void l2SkipsDesignApprovalOnlyBelowRisk30() {
    assertThat(ENGINE.stageApprovalRequired(stage("design"), L2_TRUSTED, OptionalInt.of(29)))
        .isFalse();
    assertThat(ENGINE.stageApprovalRequired(stage("design"), L2_TRUSTED, OptionalInt.of(30)))
        .isTrue();
    assertThat(ENGINE.stageApprovalRequired(stage("design"), L2_TRUSTED, OptionalInt.empty()))
        .as("unknown risk: no relaxation")
        .isTrue();
    assertThat(ENGINE.stageApprovalRequired(stage("design"), L1_SUPERVISED, OptionalInt.of(0)))
        .isTrue();
    assertThat(ENGINE.stageApprovalRequired(stage("requirements"), L2_TRUSTED, OptionalInt.of(0)))
        .as("only design is relaxed")
        .isTrue();
    assertThat(ENGINE.stageApprovalRequired(stage("docs_draft"), L0_SUGGEST, OptionalInt.empty()))
        .isFalse();
  }

  @ParameterizedTest
  @EnumSource(AutonomyLevel.class)
  void publishStageAlwaysNeedsApproval(AutonomyLevel level) {
    WorkflowDefinition noApprovalConfigured =
        new WorkflowLoader()
            .parse(
                """
                workflow: t
                budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 1 }
                stages:
                  - id: publish
                    action: git.mergeToMain
                """,
                "t.yml");

    assertThat(ENGINE.stageApprovalRequired(stage("publish"), level, OptionalInt.of(0))).isTrue();
    assertThat(
            ENGINE.stageApprovalRequired(
                noApprovalConfigured.stage("publish").orElseThrow(), level, OptionalInt.of(0)))
        .isTrue();
  }
}
