package com.acme.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowLoaderTest {

  /** Surefire runs in the module directory; the shared workflows live at the repo root. */
  private static final Path STANDARD = Path.of("..", "config", "workflows", "sdlc-standard.yml");

  private final WorkflowLoader loader = new WorkflowLoader();

  @Test
  void loadsTheStandardWorkflow() {
    WorkflowDefinition wf = loader.load(STANDARD);

    assertThat(wf.id()).isEqualTo("sdlc-standard");
    assertThat(wf.version()).isEqualTo(1);
    assertThat(wf.budgets()).isEqualTo(new Budgets(Duration.ofMinutes(30), 60, 400_000));
    assertThat(wf.stages())
        .extracting(StageDefinition::id)
        .containsExactly(
            "requirements",
            "decomposition",
            "impact_analysis",
            "design",
            "implementation",
            "test_authoring",
            "docs_draft",
            "validation",
            "docs_finalize",
            "release_readiness",
            "publish");

    StageDefinition requirements = wf.stage("requirements").orElseThrow();
    assertThat(requirements.executor()).isEqualTo(new Executor.Agent("RequirementsAgent"));
    assertThat(requirements.entryGates()).containsExactly(GateRef.parse("input.nonEmpty"));
    assertThat(requirements.exitGates())
        .containsExactly(
            new GateRef("artifact.schemaValid", Optional.of("requirements_spec")),
            new GateRef("spec.noBlockingQuestions", Optional.empty()));
    assertThat(requirements.approval()).isEqualTo(new Approval(true, "Scope sign-off"));
    // workflow defaults applied
    assertThat(requirements.retry())
        .isEqualTo(
            new RetryPolicy(
                2,
                RetryPolicy.Backoff.EXPONENTIAL,
                Duration.ofSeconds(2),
                Duration.ofSeconds(20),
                true));
    assertThat(requirements.timeout()).isEqualTo(Duration.ofMinutes(5));

    assertThat(wf.stage("impact_analysis").orElseThrow().condition())
        .contains("run.mode == 'BROWNFIELD'");
    assertThat(wf.stage("design").orElseThrow().dependsOn())
        .containsExactly("decomposition", "impact_analysis");

    StageDefinition impl = wf.stage("implementation").orElseThrow();
    assertThat(impl.expand()).contains("tasks");
    assertThat(impl.checkpoint()).isEqualTo(CheckpointKind.GIT);
    assertThat(impl.retry().maxAttempts()).isEqualTo(3); // stage override...
    assertThat(impl.retry().baseDelay()).isEqualTo(Duration.ofSeconds(2)); // ...on top of defaults
    assertThat(impl.fallback()).contains(new FallbackPolicy("minimal-patch"));

    StageDefinition validation = wf.stage("validation").orElseThrow();
    assertThat(validation.exitGates()).contains(new GateRef("coverage.min", Optional.of("0.80")));
    assertThat(validation.onFailure())
        .contains(new OnFailure("implementation", 2, Optional.of("validation_report")));

    StageDefinition publish = wf.stage("publish").orElseThrow();
    assertThat(publish.executor()).isEqualTo(new Executor.Action("git.mergeToMain"));
    assertThat(publish.highImpact()).isTrue();
    assertThat(wf.stage("docs_draft").orElseThrow().highImpact()).isFalse();
    assertThat(wf.stage("docs_draft").orElseThrow().approval()).isEqualTo(Approval.NONE);
  }

  @Test
  void minimalWorkflowGetsBuiltInDefaults() {
    WorkflowDefinition wf =
        loader.parse(
            """
            workflow: tiny
            budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 10 }
            stages:
              - id: only
                agent: A
            """,
            "tiny.yml");

    StageDefinition only = wf.stages().getFirst();
    assertThat(wf.version()).isEqualTo(1);
    assertThat(only.retry()).isEqualTo(RetryPolicy.NONE);
    assertThat(only.timeout()).isEqualTo(WorkflowLoader.DEFAULT_TIMEOUT);
    assertThat(only.checkpoint()).isEqualTo(CheckpointKind.NONE);
    assertThat(only.dependsOn()).isEmpty();
  }

  @Test
  void reportsEveryProblemAtOnceWithLocations() {
    List<String> problems =
        problems(
            """
            workflow: broken
            budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 10 }
            stages:
              - id: a
                agent: A
                dependOn: [b]
              - id: b
                agent: B
                action: x.y
              - id: c
                retry: { maxAttempts: 99 }
                timeout: soon
            """);

    assertThat(problems)
        .anySatisfy(p -> assertThat(p).startsWith("stages[0] (a).dependOn: unknown key"))
        .anySatisfy(
            p -> assertThat(p).isEqualTo("stages[1] (b): needs exactly one of 'agent' or 'action'"))
        .anySatisfy(
            p -> assertThat(p).isEqualTo("stages[2] (c): needs exactly one of 'agent' or 'action'"))
        .anySatisfy(p ->
            assertThat(p).startsWith("stages[2] (c).retry.maxAttempts: must be between 1 and 10"))
        .anySatisfy(p -> assertThat(p).startsWith("stages[2] (c).timeout: must be a duration"));
  }

  @Test
  void rejectsUnknownAndSelfDependencies() {
    assertThat(
            problems(
                stages(
                    """
                      - id: a
                        agent: A
                        dependsOn: [a, ghost]
                    """)))
        .contains(
            "stage a.dependsOn: a stage can't depend on itself",
            "stage a.dependsOn: unknown stage 'ghost'");
  }

  @Test
  void rejectsCyclesAndNamesThePath() {
    assertThat(
            problems(
                stages(
                    """
                      - id: a
                        agent: A
                        dependsOn: [c]
                      - id: b
                        agent: B
                        dependsOn: [a]
                      - id: c
                        agent: C
                        dependsOn: [b]
                    """)))
        .containsExactly("stages: dependency cycle: a -> c -> b -> a");
  }

  @Test
  void rejectsDuplicateIdsAndDuplicateDependencies() {
    assertThat(
            problems(
                stages(
                    """
                      - id: a
                        agent: A
                      - id: a
                        agent: B
                      - id: b
                        agent: B
                        dependsOn: [a, a]
                    """)))
        .contains("stages: duplicate stage id 'a'", "stage b.dependsOn: 'a' is listed twice");
  }

  @Test
  void loopToMustBeUpstream() {
    assertThat(
            problems(
                stages(
                    """
                      - id: impl
                        agent: A
                      - id: validate
                        agent: V
                        onFailure: { loopTo: docs, maxLoops: 2 }
                      - id: docs
                        agent: D
                        dependsOn: [validate]
                    """)))
        .containsExactly("stage validate.onFailure.loopTo: 'docs' must be upstream of 'validate'");
  }

  @Test
  void rejectsBadValues() {
    List<String> problems =
        problems(
            stages(
                """
                  - id: Bad-Id
                    agent: A
                    expand: files
                    checkpoint: svn
                    highImpact: "yes"
                    exitGates: [notagate, "coverage.min:"]
                    approval: { required: true }
                    retry: { backoff: linear, baseDelay: 10s, maxDelay: 1s }
                """));

    assertThat(problems)
        .anySatisfy(p -> assertThat(p).contains(".id: must match"))
        .anySatisfy(p -> assertThat(p).contains(".expand: unsupported: 'files'"))
        .anySatisfy(p -> assertThat(p).contains(".checkpoint: must be 'none' or 'git'"))
        .anySatisfy(p -> assertThat(p).contains(".highImpact: must be true or false"))
        .anySatisfy(p -> assertThat(p).contains(".exitGates[0]: gate name must look like"))
        .anySatisfy(p -> assertThat(p).contains(".exitGates[1]: gate argument must not be blank"))
        .anySatisfy(p -> assertThat(p).contains(".approval: a required approval needs a reason"))
        .anySatisfy(p -> assertThat(p).contains(".retry.backoff: must be 'fixed' or 'exponential'"))
        .anySatisfy(p -> assertThat(p).contains(".retry: need 0 <= baseDelay <= maxDelay"));
  }

  @Test
  void requiresTheTopLevelBasics() {
    assertThat(problems("stages: []\n"))
        .contains(
            "workflow: is required",
            "budgets: is required",
            "stages: at least one stage is required");
  }

  @Test
  void rejectsSomethingThatIsNotAWorkflow() {
    assertThat(problems("- just\n- a list\n")).containsExactly("the document must be a mapping");
    assertThat(problems("workflow: [unclosed\n"))
        .singleElement()
        .asString()
        .startsWith("not valid YAML");
  }

  @Test
  void exceptionMessageListsTheSourceAndProblems() {
    assertThatThrownBy(() -> loader.parse("stages: []\n", "broken.yml"))
        .isInstanceOf(WorkflowValidationException.class)
        .hasMessageContaining("invalid workflow broken.yml")
        .hasMessageContaining("- workflow: is required");
  }

  private List<String> problems(String yaml) {
    try {
      loader.parse(yaml, "test.yml");
    } catch (WorkflowValidationException e) {
      return e.problems();
    }
    throw new AssertionError("expected the workflow to be rejected");
  }

  private static String stages(String stageYaml) {
    return """
    workflow: t
    budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 10 }
    stages:
    """
        + stageYaml;
  }
}
