package com.acme.orchestrator.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyLoaderTest {

  private static final String AUTONOMY =
      """
      levels:
        L0_SUGGEST:    { description: "suggest" }
        L1_SUPERVISED: { description: "supervised" }
        L2_TRUSTED:    { description: "trusted" }
      invariants:
        - publish requires human approval
        - DENY policies are never overridable
        - approvals must come from a human principal with role APPROVER
      """;

  private static final String ONE_POLICY =
      """
      policies:
        - id: P-1
          on: command
          allow: ["mvn test"]
          effect_outside: DENY
      """;

  private final PolicyLoader loader = new PolicyLoader();

  private List<String> problems(String policies, String autonomy) {
    try {
      loader.parse(policies, autonomy);
    } catch (PolicyValidationException e) {
      return e.problems();
    }
    throw new AssertionError("expected the config to be rejected");
  }

  @Test
  void loadsEveryRuleKind() {
    PolicySet set =
        loader.parse(
            """
            policies:
              - { id: A, on: diff, deny_patterns: ["x+"], effect: DENY }
              - { id: B, on: diff, max_lines_changed: 10, effect: REQUIRE_APPROVAL, weight: 7 }
              - { id: C, on: file_write, allow_paths: ["src/**"], effect_outside: DENY }
              - { id: D, on: file_write, paths: ["**/pom.xml"], effect: REQUIRE_APPROVAL, message: "deps" }
              - { id: E, on: command, allow: ["mvn test"], effect_outside: DENY }
            """,
            AUTONOMY);

    assertThat(set.policies())
        .hasExactlyElementsOfTypes(
            Policy.DenyPatterns.class,
            Policy.SizeLimit.class,
            Policy.PathJail.class,
            Policy.PathRule.class,
            Policy.CommandAllowlist.class);
    assertThat(set.policies()).extracting(Policy::weight).containsExactly(50, 7, 50, 20, 50);
    assertThat(set.policies().get(3).message()).contains("deps");
    assertThat(((Policy.SizeLimit) set.policies().get(1)).maxFiles()).isEmpty();
  }

  @Test
  void collectsEveryProblemWithItsLocation() {
    assertThat(
            problems(
                """
                policies:
                  - { id: A, on: diff, deny_patterns: ["(unclosed"], effect: DENY }
                  - { id: B, on: file_write, paths: ["x"], effect: ALLOW }
                  - { id: C, on: file_write, paths: ["x"], effect: DENY, typo: 1 }
                  - { id: D, on: network, effect: DENY }
                  - { id: E, on: diff, max_files_changed: 0, effect: DENY }
                  - { id: F, on: command, allow: [], effect_outside: DENY, weight: 101 }
                  - { id: A, on: command, allow: ["x"], effect_outside: DENY }
                  - { on: diff }
                  - { id: G, on: file_write, effect: DENY }
                  - { id: H, on: file_write, allow_paths: ["x"] }
                """,
                AUTONOMY))
        .containsExactly(
            "policies.yml: policies[0] (A): deny_patterns: invalid regex '(unclosed': Unclosed group",
            "policies.yml: policies[1] (B): 'effect' must be DENY or REQUIRE_APPROVAL, was 'ALLOW'",
            "policies.yml: policies[2] (C): unknown key 'typo' for this kind of rule",
            "policies.yml: policies[3] (D): 'on' must be diff, file_write or command, was 'network'",
            "policies.yml: policies[4] (E): 'max_files_changed' must be a positive whole number",
            "policies.yml: policies[5] (F): 'allow' must be a non-empty list",
            "policies.yml: policies[5] (F): 'weight' must be a whole number from 0 to 100",
            "policies.yml: policies[6] (A): duplicate policy id A",
            "policies.yml: policies[7]: 'id' is required",
            "policies.yml: policies[8] (G): a file_write rule needs allow_paths or paths",
            "policies.yml: policies[9] (H): 'effect_outside' is required");
  }

  @Test
  void theAutonomyFileMustMatchTheLevelsAndInvariantsInCode() {
    assertThat(
            problems(
                ONE_POLICY,
                """
                levels:
                  L0_SUGGEST:    { description: "suggest" }
                  L1_SUPERVISED: { description: "" }
                  L3_YOLO:       { description: "anything goes" }
                invariants:
                  - publish requires human approval
                  - agents may approve their own work
                """))
        .containsExactly(
            "autonomy.yml: levels.L1_SUPERVISED: needs a non-empty 'description'",
            "autonomy.yml: levels.L3_YOLO: unknown autonomy level",
            "autonomy.yml: levels: L2_TRUSTED is missing",
            "autonomy.yml: invariant 'agents may approve their own work' is not one the engine"
                + " enforces",
            "autonomy.yml: invariant 'DENY policies are never overridable' is missing (it is"
                + " enforced in code regardless)",
            "autonomy.yml: invariant 'approvals must come from a human principal with role"
                + " APPROVER' is missing (it is enforced in code regardless)");
  }

  @Test
  void theAutonomyLineAsWrittenInThePlanDoesNotParse() {
    // BUILD_PLAN §6.5 has "L1_SUPERVISED:{ description: ...}" (no space after the colon)
    String planText =
        AUTONOMY.replace("L1_SUPERVISED: { description", "L1_SUPERVISED:{ description");

    assertThat(problems(ONE_POLICY, planText))
        .singleElement()
        .asString()
        .startsWith("autonomy.yml: not valid YAML");
  }

  @Test
  void brokenDocumentsAreReportedPerFile() {
    assertThat(problems("- just a list", "levels: [")).hasSize(2);
    assertThat(problems("- just a list", AUTONOMY))
        .containsExactly("policies.yml: the document must be a mapping");
    assertThat(problems("{ policies: 3 }", AUTONOMY))
        .containsExactly("policies.yml: 'policies' must be a list");
  }

  @Test
  void hashesIgnoreLineEndingsButNotContent() {
    PolicySet lf = loader.parse(ONE_POLICY, AUTONOMY);
    PolicySet crlf = loader.parse(ONE_POLICY.replace("\n", "\r\n"), AUTONOMY.replace("\n", "\r\n"));
    PolicySet edited = loader.parse(ONE_POLICY.replace("mvn test", "mvn verify"), AUTONOMY);

    assertThat(crlf.policiesHash()).isEqualTo(lf.policiesHash());
    assertThat(crlf.autonomyHash()).isEqualTo(lf.autonomyHash());
    assertThat(edited.policiesHash()).isNotEqualTo(lf.policiesHash());
    assertThat(edited.autonomyHash()).isEqualTo(lf.autonomyHash());
  }

  @Test
  void aMissingFileIsAnError() {
    assertThatThrownBy(() -> loader.load(java.nio.file.Path.of("no-such-dir")))
        .hasMessageContaining("cannot read");
  }
}
