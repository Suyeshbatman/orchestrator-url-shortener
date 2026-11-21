package com.acme.orchestrator.core.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.engine.GateResult;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.model.WorkflowValidationException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GateRegistryTest {

  private final GateRegistry registry = GateRegistry.standard(SchemaValidator.STRUCTURE_ONLY);

  private static String workflowWithGates(String gates) {
    return """
    workflow: t
    budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 1 }
    stages:
      - id: a
        agent: A
        exitGates: %s
    """
        .formatted(gates);
  }

  @Test
  void theStandardRegistryHoldsEveryGateOfTheStandardWorkflow() {
    assertThat(registry.names())
        .containsExactly(
            "artifact.schemaValid",
            "build.green",
            "coverage.min",
            "design.apiContractPresent",
            "input.nonEmpty",
            "policy.noDeny",
            "release.checklistComplete",
            "spec.noBlockingQuestions",
            "taskGraph.acyclic",
            "taskGraph.everyRequirementCovered",
            "tests.green");

    WorkflowDefinition standard =
        new WorkflowLoader(registry).load(Path.of("../config/workflows/sdlc-standard.yml"));
    assertThat(standard.stages()).hasSize(11);
  }

  @Test
  void anUnknownGateIsAWorkflowLoadError() {
    assertThatThrownBy(
            () -> new WorkflowLoader(registry).parse(workflowWithGates("[build.gren]"), "t.yml"))
        .isInstanceOfSatisfying(
            WorkflowValidationException.class,
            e ->
                assertThat(e.problems())
                    .singleElement()
                    .asString()
                    .startsWith("stages[0] (a).exitGates[0]: unknown gate 'build.gren' (known: ")
                    .contains("build.green"));

    // without a catalog, only the syntax is checked
    assertThatCode(() -> new WorkflowLoader().parse(workflowWithGates("[build.gren]"), "t.yml"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "build.green:now",
        "artifact.schemaValid",
        "coverage.min",
        "coverage.min:lots",
        "coverage.min:1.5",
        "coverage.min:-0.1"
      })
  void badArgumentsAreLoadErrors(String gate) {
    assertThatThrownBy(() -> registry.check(GateRef.parse(gate)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void argumentErrorsSayWhatIsExpected() {
    assertThatThrownBy(() -> registry.check(GateRef.parse("build.green:now")))
        .hasMessage("takes no argument, got 'now'");
    assertThatThrownBy(() -> registry.check(GateRef.parse("coverage.min")))
        .hasMessage("needs an argument");
    assertThatThrownBy(() -> registry.check(GateRef.parse("coverage.min:80%")))
        .hasMessage("needs a fraction between 0 and 1, e.g. coverage.min:0.80, got '80%'");
  }

  @Test
  void goodArgumentsAreAccepted() {
    for (String gate : List.of("coverage.min:0", "coverage.min:1", "coverage.min:0.80")) {
      assertThatCode(() -> registry.check(GateRef.parse(gate))).doesNotThrowAnyException();
    }
    assertThatCode(() -> registry.check(GateRef.parse("artifact.schemaValid:task_graph")))
        .doesNotThrowAnyException();
  }

  @Test
  void schemaValidNeedsASchemaForTheType() {
    SchemaValidator onlySpecs =
        new SchemaValidator() {
          @Override
          public boolean supports(String artifactType) {
            return Set.of("requirements_spec").contains(artifactType);
          }

          @Override
          public List<String> validate(String artifactType, String contentJson) {
            return List.of();
          }
        };
    GateRegistry strict = GateRegistry.standard(onlySpecs);

    assertThatCode(() -> strict.check(GateRef.parse("artifact.schemaValid:requirements_spec")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> strict.check(GateRef.parse("artifact.schemaValid:tsk_graph")))
        .hasMessage("no schema for artifact type 'tsk_graph'");
  }

  @Test
  void evaluatingAnUnknownGateFailsInsteadOfThrowing() {
    assertThat(registry.evaluate(GateRef.parse("build.gren"), null))
        .isEqualTo(GateResult.fail("unknown gate 'build.gren'"));
  }

  @Test
  void customGatesCanBeRegistered() {
    GateRegistry custom =
        GateRegistry.builder()
            .register("demo.always", (argument, ctx) -> GateResult.pass("ok"))
            .build();

    assertThat(custom.names()).containsExactly("demo.always");
    assertThat(custom.evaluate(GateRef.parse("demo.always"), null).passed()).isTrue();
    assertThatThrownBy(() ->
            GateRegistry.builder()
                .register("demo.always", (a, c) -> GateResult.pass())
                .register("demo.always", (a, c) -> GateResult.pass()))
        .hasMessage("gate already registered: demo.always");
    assertThatThrownBy(() -> GateRegistry.builder().register("nodot", (a, c) -> GateResult.pass()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aGateReturningNullIsAnError() {
    GateRegistry broken =
        GateRegistry.builder().register("demo.broken", (argument, ctx) -> null).build();

    assertThatThrownBy(() -> broken.evaluate(GateRef.parse("demo.broken"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("gate demo.broken returned null");
  }
}
