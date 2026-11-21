package com.acme.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invariants enforced by the value types themselves. */
class ModelRecordsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void artifactHashIsComputedAndChecked() {
    Artifact a =
        Artifact.create(
            "a-1",
            "run-1",
            "requirements",
            "requirements_spec",
            1,
            "{\"goal\":\"qr codes\"}",
            ProducedBy.llm("RequirementsAgent", "claude-sonnet-5-5", "abc"),
            List.of(),
            "from the user story",
            CLOCK);

    assertThat(a.contentHash()).isEqualTo(Hashing.sha256Hex("{\"goal\":\"qr codes\"}"));
    assertThat(a.ref()).isEqualTo(new ArtifactRef("a-1", "requirements_spec", 1, a.contentHash()));
    assertThat(a.createdAt()).isEqualTo(CLOCK.instant());

    // Tampered content with the old hash can't be constructed.
    assertThatThrownBy(() ->
            new Artifact(
                a.id(),
                a.runId(),
                a.stageId(),
                a.type(),
                a.version(),
                "{\"goal\":\"something else\"}",
                a.contentHash(),
                a.producedBy(),
                a.inputs(),
                a.rationale(),
                a.createdAt()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contentHash");
  }

  @Test
  void artifactInputsAreCopiedAndVersionStartsAtOne() {
    List<ArtifactRef> inputs = new java.util.ArrayList<>();
    inputs.add(new ArtifactRef("a-0", "requirements_spec", 1, Hashing.sha256Hex("x")));
    Artifact a =
        Artifact.create(
            "a-1",
            "r",
            "decomposition",
            "task_graph",
            1,
            "{}",
            ProducedBy.deterministic("DecompositionAgent"),
            inputs,
            null,
            CLOCK);
    inputs.clear();

    assertThat(a.inputs()).hasSize(1);
    assertThat(a.rationale()).isEmpty();
    assertThat(a.producedBy().model()).isEmpty();
    assertThatThrownBy(() ->
            Artifact.create(
                "a-2", "r", "s", "t", 0, "{}", ProducedBy.deterministic("x"), List.of(), "", CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hashingIsLowercaseHexSha256() {
    assertThat(Hashing.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void gateRefParsesNameAndArgument() {
    assertThat(GateRef.parse("coverage.min:0.80"))
        .isEqualTo(new GateRef("coverage.min", Optional.of("0.80")));
    assertThat(GateRef.parse("taskGraph.acyclic").argument()).isEmpty();
    assertThat(GateRef.parse("artifact.schemaValid:requirements_spec"))
        .hasToString("artifact.schemaValid:requirements_spec");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "nodot", "has space.x", ".leading", "a.", "x.y:", "1a.b"})
  void gateRefRejectsBadSyntax(String spec) {
    assertThatThrownBy(() -> GateRef.parse(spec)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void runStartsCreatedWithTheWorkflowBudgets() {
    Budgets budgets = new Budgets(Duration.ofMinutes(30), 60, 400_000);
    WorkflowDefinition wf = new WorkflowDefinition("wf", 1, budgets, List.of());

    Run run = Run.create("run-1", wf, RunMode.BROWNFIELD, AutonomyLevel.L1_SUPERVISED);

    assertThat(run.status()).isEqualTo(RunStatus.CREATED);
    assertThat(run.budgets()).isEqualTo(budgets);
    assertThat(run.withStatus(RunStatus.RUNNING).status()).isEqualTo(RunStatus.RUNNING);
    assertThat(RunStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(RunStatus.HALTED.isTerminal()).isFalse(); // resumable
  }

  @Test
  void valueTypesRejectNonsense() {
    assertThatThrownBy(() -> new Budgets(Duration.ZERO, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Budgets(Duration.ofMinutes(1), -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new RetryPolicy(0, RetryPolicy.Backoff.FIXED, Duration.ZERO, Duration.ZERO, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new RetryPolicy(
                2, RetryPolicy.Backoff.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(1), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Approval(true, " ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OnFailure("impl", 0, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Executor.Agent(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FallbackPolicy("")).isInstanceOf(IllegalArgumentException.class);
  }
}
