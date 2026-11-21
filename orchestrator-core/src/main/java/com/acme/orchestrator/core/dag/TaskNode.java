package com.acme.orchestrator.core.dag;

import java.util.List;
import java.util.Objects;

/**
 * One node of a subgraph passed to {@link Dag#expand}.
 *
 * @param dependsOn ids of other tasks in the same expansion (not of outside stages)
 */
public record TaskNode<T>(T id, List<T> dependsOn) {

  public TaskNode {
    Objects.requireNonNull(id, "id");
    dependsOn = List.copyOf(dependsOn);
  }

  public static <T> TaskNode<T> of(T id, List<T> dependsOn) {
    return new TaskNode<>(id, dependsOn);
  }
}
