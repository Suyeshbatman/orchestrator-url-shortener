package com.acme.orchestrator.core.dag;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The graph has a cycle. {@link #cycle()} is one cycle in edge direction, first node repeated at
 * the end: {@code [a, b, c, a]} means a → b → c → a (each must run before the next).
 */
public final class CycleDetectedException extends RuntimeException {

  private final List<Object> cycle;

  public CycleDetectedException(List<?> cycle) {
    super(
        "cycle detected: "
            + cycle.stream().map(String::valueOf).collect(Collectors.joining(" -> ")));
    this.cycle = List.copyOf(cycle);
  }

  public List<Object> cycle() {
    return cycle;
  }
}
