package com.acme.orchestrator.core.artifact;

import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.model.Artifact;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An artifact and everything it was derived from. {@code graph} has an edge input → derived for
 * every recorded input, so {@code graph.topologicalOrder()} lists sources first.
 */
public record Lineage(Artifact root, Map<String, Artifact> artifacts, Dag<String> graph) {

  public Lineage {
    artifacts = Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
    graph = graph.copy();
  }

  /** Ancestors only (the root excluded), sources first. */
  public List<Artifact> ancestors() {
    return graph.topologicalOrder().stream()
        .filter(id -> !id.equals(root.id()))
        .map(artifacts::get)
        .toList();
  }
}
