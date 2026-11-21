package com.acme.orchestrator.core.dag;

import static com.acme.orchestrator.core.model.StageStatus.FAILED;
import static com.acme.orchestrator.core.model.StageStatus.RUNNING;
import static com.acme.orchestrator.core.model.StageStatus.SKIPPED;
import static com.acme.orchestrator.core.model.StageStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.StageStatus;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagTest {

  /** a → b, a → c, b → d, c → d: a fork and a join. */
  private static Dag<String> diamond() {
    return new Dag<String>()
        .addEdge("a", "b")
        .addEdge("a", "c")
        .addEdge("b", "d")
        .addEdge("c", "d");
  }

  @Test
  void topologicalOrderOfADiamondIsDeterministic() {
    assertThat(diamond().topologicalOrder()).containsExactly("a", "b", "c", "d");
  }

  @Test
  void levelsGroupWhatCanRunInParallel() {
    Dag<String> dag = diamond().addEdge("a", "e"); // e only needs a
    assertThat(dag.levels()).containsExactly(List.of("a"), List.of("b", "c", "e"), List.of("d"));
  }

  @Test
  void levelUsesTheDeepestDependency() {
    Dag<String> dag = new Dag<String>().addEdge("a", "b").addEdge("b", "c").addEdge("a", "c");
    assertThat(dag.levels()).containsExactly(List.of("a"), List.of("b"), List.of("c"));
  }

  @Test
  void isolatedNodesAreLevelZero() {
    Dag<String> dag = new Dag<String>().addNode("x").addEdge("a", "b");
    assertThat(dag.levels()).containsExactly(List.of("x", "a"), List.of("b"));
  }

  @Test
  void cycleIsDetectedAndItsPathNamed() {
    Dag<String> dag =
        new Dag<String>()
            .addEdge("start", "a")
            .addEdge("a", "b")
            .addEdge("b", "c")
            .addEdge("c", "a");

    assertThatThrownBy(dag::topologicalOrder)
        .isInstanceOfSatisfying(
            CycleDetectedException.class,
            e -> assertThat(e.cycle()).containsExactly("a", "b", "c", "a"))
        .hasMessage("cycle detected: a -> b -> c -> a");
    assertThatThrownBy(dag::levels).isInstanceOf(CycleDetectedException.class);
    assertThatThrownBy(dag::validateAcyclic).isInstanceOf(CycleDetectedException.class);
  }

  @Test
  void selfLoopIsRejectedImmediately() {
    assertThatThrownBy(() -> new Dag<String>().addEdge("a", "a"))
        .isInstanceOf(CycleDetectedException.class)
        .hasMessage("cycle detected: a -> a");
  }

  @Test
  void readyNodesWaitForEveryDependencyAtTheJoin() {
    Dag<String> dag = diamond();

    assertThat(dag.readyNodes(Map.of())).containsExactly("a");
    assertThat(dag.readyNodes(Map.of("a", SUCCEEDED))).containsExactly("b", "c");
    assertThat(dag.readyNodes(Map.of("a", SUCCEEDED, "b", SUCCEEDED, "c", RUNNING))).isEmpty();
    assertThat(dag.readyNodes(Map.of("a", SUCCEEDED, "b", SUCCEEDED, "c", SKIPPED)))
        .containsExactly("d"); // SKIPPED satisfies the join
    assertThat(dag.readyNodes(Map.of("a", SUCCEEDED, "b", SUCCEEDED, "c", FAILED))).isEmpty();
  }

  @Test
  void readyNodesSkipsWhatIsAlreadyUnderway() {
    Map<String, StageStatus> status = Map.of("a", SUCCEEDED, "b", RUNNING);
    assertThat(diamond().readyNodes(status)).containsExactly("c");
  }

  @Test
  void closuresFollowEdgesTransitively() {
    Dag<String> dag = diamond().addEdge("d", "e").addNode("lonely");

    assertThat(dag.downstreamClosure("b")).containsExactly("d", "e");
    assertThat(dag.downstreamClosure("a")).containsExactlyInAnyOrder("b", "c", "d", "e");
    assertThat(dag.upstreamClosure("d")).containsExactlyInAnyOrder("b", "c", "a");
    assertThat(dag.upstreamClosure("a")).isEmpty();
    assertThat(dag.downstreamClosure("lonely")).isEmpty();
  }

  @Test
  void expansionWiresRootsToUpstreamAndSinksToDownstream() {
    // design → impl → validate, docs also after design
    Dag<String> dag =
        new Dag<String>()
            .addEdge("design", "impl")
            .addEdge("impl", "validate")
            .addEdge("design", "docs");

    // T1 and T2 independent, T3 needs both
    dag.expand(
        "impl",
        List.of(
            TaskNode.of("impl/T1", List.of()),
            TaskNode.of("impl/T2", List.of()),
            TaskNode.of("impl/T3", List.of("impl/T1", "impl/T2"))));

    assertThat(dag.contains("impl")).isFalse();
    assertThat(dag.dependenciesOf("impl/T1")).containsExactly("design");
    assertThat(dag.dependenciesOf("impl/T2")).containsExactly("design");
    assertThat(dag.dependenciesOf("impl/T3")).containsExactly("impl/T1", "impl/T2");
    assertThat(dag.dependenciesOf("validate")).containsExactly("impl/T3"); // only the sink
    assertThat(dag.levels())
        .containsExactly(
            List.of("design"),
            List.of("docs", "impl/T1", "impl/T2"),
            List.of("impl/T3"),
            List.of("validate"));
  }

  @Test
  void expansionWithSeveralSinksFeedsEachOfThemDownstream() {
    Dag<String> dag = new Dag<String>().addEdge("impl", "validate");

    dag.expand("impl", List.of(TaskNode.of("T1", List.of()), TaskNode.of("T2", List.of())));

    assertThat(dag.dependenciesOf("validate")).containsExactly("T1", "T2");
    List<String> order = dag.topologicalOrder();
    assertThat(order.indexOf("T1")).isLessThan(order.indexOf("validate"));
    assertThat(order.indexOf("T2")).isLessThan(order.indexOf("validate"));
  }

  @Test
  void invalidExpansionsLeaveTheGraphUnchanged() {
    Dag<String> dag = diamond();
    String before = dag.toString();

    assertThatThrownBy(() -> dag.expand("zzz", List.of(TaskNode.of("t", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dag.expand("b", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one task");
    assertThatThrownBy(() ->
            dag.expand("b", List.of(TaskNode.of("t", List.of()), TaskNode.of("t", List.of()))))
        .hasMessageContaining("duplicate task id t");
    assertThatThrownBy(() -> dag.expand("b", List.of(TaskNode.of("c", List.of()))))
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> dag.expand("b", List.of(TaskNode.of("t", List.of("elsewhere")))))
        .hasMessageContaining("not in this expansion");
    assertThatThrownBy(() ->
            dag.expand(
                "b", List.of(TaskNode.of("t1", List.of("t2")), TaskNode.of("t2", List.of("t1")))))
        .isInstanceOf(CycleDetectedException.class);

    assertThat(dag).hasToString(before);
  }

  @Test
  void aStageCanBeExpandedIntoATaskWithItsOwnId() {
    Dag<String> dag = diamond();
    dag.expand("b", List.of(TaskNode.of("b", List.of())));

    assertThat(dag.dependenciesOf("b")).containsExactly("a");
    assertThat(dag.dependentsOf("b")).containsExactly("d");
    assertThat(dag.dependenciesOf("d")).containsExactlyInAnyOrder("b", "c");
  }

  @Test
  void copyIsIndependent() {
    Dag<String> original = diamond();
    Dag<String> copy = original.copy();
    copy.addEdge("d", "e");

    assertThat(original.contains("e")).isFalse();
    assertThat(copy.dependentsOf("d")).containsExactly("e");
  }

  @Test
  void unknownNodesAreRejected() {
    assertThatThrownBy(() -> diamond().dependenciesOf("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> diamond().downstreamClosure("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theStandardWorkflowHasTheExpectedShape() {
    Dag<String> dag =
        WorkflowGraph.of(
            new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml")));

    assertThat(dag.levels())
        .containsExactly(
            List.of("requirements"),
            List.of("decomposition"),
            List.of("impact_analysis"),
            List.of("design"),
            List.of("implementation", "test_authoring", "docs_draft"), // parallel branches
            List.of("validation"), // JOIN of implementation + test_authoring
            List.of("docs_finalize"), // JOIN of validation + docs_draft
            List.of("release_readiness"),
            List.of("publish"));
    assertThat(dag.downstreamClosure("implementation"))
        .containsExactly("validation", "docs_finalize", "release_readiness", "publish");
  }
}
