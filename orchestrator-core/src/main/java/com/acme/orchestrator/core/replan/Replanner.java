package com.acme.orchestrator.core.replan;

import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.model.StageEvent;
import com.acme.orchestrator.core.model.StageStateMachine;
import com.acme.orchestrator.core.model.StageStatus;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what a change invalidates. Pure: the coordinator applies the result (STAGE_INVALIDATED
 * per stage, rollback to the earliest checkpoint, voided approvals) and the scheduler re-runs the
 * invalidated stages as they become ready.
 *
 * <p>Triggers (BUILD_PLAN Step 4.5): an amended requirement re-runs the stages that read it; a
 * rejection re-runs the stage; a stage whose re-run changed an artifact's content invalidates what
 * consumed the old version; a changed task set re-expands the implementation (see {@link
 * TaskDiff}).
 */
public final class Replanner {

  private Replanner() {}

  /**
   * The stages to invalidate, in topological order: the {@code changed} stages (if {@code
   * includeChanged}) and everything downstream of them, limited to stages that have started (a
   * PENDING stage simply runs later against the new inputs) and whose status allows invalidation.
   */
  public static List<String> affected(
      Dag<String> dag,
      Map<String, StageStatus> statuses,
      Collection<String> changed,
      boolean includeChanged) {
    Set<String> candidates = new LinkedHashSet<>();
    for (String stage : changed) {
      if (includeChanged) {
        candidates.add(stage);
      }
      candidates.addAll(dag.downstreamClosure(stage));
    }
    return dag.topologicalOrder().stream()
        .filter(candidates::contains)
        .filter(s ->
            StageStateMachine.canTransition(
                statuses.getOrDefault(s, StageStatus.PENDING), StageEvent.INVALIDATE))
        .toList();
  }
}
