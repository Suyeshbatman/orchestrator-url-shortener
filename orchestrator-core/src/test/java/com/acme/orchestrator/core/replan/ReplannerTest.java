package com.acme.orchestrator.core.replan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.model.StageStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplannerTest {

  /** a → b → d, a → c → d, x (unrelated). */
  private static Dag<String> diamond() {
    return new Dag<String>()
        .addEdge("a", "b")
        .addEdge("a", "c")
        .addEdge("b", "d")
        .addEdge("c", "d")
        .addNode("x");
  }

  @Test
  void theChangedStageAndEverythingDownstreamThatStartedInTopologicalOrder() {
    Map<String, StageStatus> statuses =
        Map.of(
            "a", StageStatus.SUCCEEDED,
            "b", StageStatus.SUCCEEDED,
            "c", StageStatus.WAITING_APPROVAL,
            "x", StageStatus.SUCCEEDED); // d has not started

    assertThat(Replanner.affected(diamond(), statuses, List.of("a"), true))
        .containsExactly("a", "b", "c");
    assertThat(Replanner.affected(diamond(), statuses, List.of("a"), false))
        .containsExactly("b", "c");
  }

  @Test
  void stagesThatCantBeInvalidatedAreLeftAlone() {
    Map<String, StageStatus> statuses =
        Map.of(
            "a", StageStatus.SUCCEEDED,
            "b", StageStatus.RETRYING, // re-runs anyway, against the new inputs
            "c", StageStatus.INVALIDATED, // already
            "d", StageStatus.HALTED); // resets on resume

    assertThat(Replanner.affected(diamond(), statuses, List.of("a"), true)).containsExactly("a");
  }

  @Test
  void severalChangedStagesAreMerged() {
    Map<String, StageStatus> statuses =
        Map.of(
            "b", StageStatus.SUCCEEDED,
            "c", StageStatus.SUCCEEDED,
            "d", StageStatus.RUNNING);

    assertThat(Replanner.affected(diamond(), statuses, List.of("c", "b"), true))
        .containsExactly("b", "c", "d");
  }

  // ---- task diff ------------------------------------------------------------------------------

  @Test
  void taskDiffSortsTasksIntoUnchangedChangedRemovedAndAdded() {
    TaskDiff diff =
        TaskDiff.of(
            """
            {"tasks": [
              {"id": "T1", "title": "QR endpoint", "dependsOn": []},
              {"id": "T2", "title": "PNG renderer"},
              {"id": "T3", "title": "docs"}]}
            """,
            """
            {"tasks": [
              {"dependsOn": [], "title": "QR endpoint", "id": "T1"},
              {"id": "T2", "title": "SVG renderer"},
              {"id": "T4", "title": "expiry"}]}
            """);

    assertThat(diff.unchanged()).containsExactly("T1"); // key order doesn't count
    assertThat(diff.changed()).containsExactly("T2");
    assertThat(diff.removed()).containsExactly("T3");
    assertThat(diff.added()).containsExactly("T4");
    assertThat(diff.sameTasks()).isFalse();
  }

  @Test
  void identicalTaskGraphsHaveNoDifferences() {
    String graph = "{\"tasks\": [{\"id\": \"T1\", \"risk\": \"LOW\"}]}";

    assertThat(TaskDiff.of(graph, graph).sameTasks()).isTrue();
  }

  @Test
  void malformedTaskGraphsAreRejected() {
    assertThatThrownBy(() -> TaskDiff.of("{}", "{\"tasks\": []}"))
        .hasMessage("a task graph needs a 'tasks' list");
    assertThatThrownBy(() -> TaskDiff.of("{\"tasks\": [{\"title\": \"x\"}]}", "{\"tasks\": []}"))
        .hasMessage("every task needs an id");
    assertThatThrownBy(() ->
            TaskDiff.of("{\"tasks\": [{\"id\": \"T1\"}, {\"id\": \"T1\"}]}", "{\"tasks\": []}"))
        .hasMessage("duplicate task id T1");
  }
}
