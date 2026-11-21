package com.acme.orchestrator.core.artifact;

import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ArtifactStore} in memory, for tests and the CLI demo. Thread-safe. Ids are {@code
 * runId:stageId:type:vN}: deterministic, so replays produce the same ids.
 */
public final class InMemoryArtifactStore implements ArtifactStore {

  private final Clock clock;
  private final Map<String, Artifact> byId = new LinkedHashMap<>();

  public InMemoryArtifactStore(Clock clock) {
    this.clock = clock;
  }

  public static String id(String runId, String stageId, String type, int version) {
    return runId + ":" + stageId + ":" + type + ":v" + version;
  }

  @Override
  public synchronized Artifact save(ArtifactDraft d) {
    for (ArtifactRef in : d.inputs()) {
      Artifact stored = byId.get(in.artifactId());
      if (stored == null) {
        throw new IllegalArgumentException("unknown input artifact " + in.artifactId());
      }
      if (!stored.ref().equals(in)) {
        throw new IllegalArgumentException(
            "input " + in.artifactId() + " does not match the stored artifact (type/version/hash)");
      }
    }
    int version = versions(d.runId(), d.stageId(), d.type()).size() + 1;
    Artifact artifact =
        Artifact.create(
            id(d.runId(), d.stageId(), d.type(), version),
            d.runId(),
            d.stageId(),
            d.type(),
            version,
            d.contentJson(),
            d.producedBy(),
            d.inputs(),
            d.rationale(),
            clock);
    byId.put(artifact.id(), artifact);
    return artifact;
  }

  @Override
  public synchronized Optional<Artifact> get(String artifactId) {
    return Optional.ofNullable(byId.get(artifactId));
  }

  @Override
  public synchronized Optional<Artifact> latest(String runId, String stageId, String type) {
    List<Artifact> all = versions(runId, stageId, type);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
  }

  @Override
  public synchronized Optional<Artifact> latestOfType(String runId, String type) {
    Artifact latest = null;
    for (Artifact a : byId.values()) {
      if (a.runId().equals(runId) && a.type().equals(type)) {
        latest = a; // save order: the last one wins
      }
    }
    return Optional.ofNullable(latest);
  }

  @Override
  public synchronized List<Artifact> versions(String runId, String stageId, String type) {
    return byId.values().stream()
        .filter(
            a -> a.runId().equals(runId) && a.stageId().equals(stageId) && a.type().equals(type))
        .toList();
  }

  @Override
  public synchronized List<Artifact> byRun(String runId) {
    return byId.values().stream().filter(a -> a.runId().equals(runId)).toList();
  }

  @Override
  public synchronized Lineage lineage(String artifactId) {
    Artifact root = byId.get(artifactId);
    if (root == null) {
      throw new IllegalArgumentException("unknown artifact " + artifactId);
    }
    Map<String, Artifact> seen = new LinkedHashMap<>();
    Dag<String> graph = new Dag<>();
    Deque<Artifact> todo = new ArrayDeque<>(List.of(root));
    seen.put(root.id(), root);
    graph.addNode(root.id());
    while (!todo.isEmpty()) {
      Artifact a = todo.poll();
      for (ArtifactRef in : a.inputs()) {
        Artifact input = byId.get(in.artifactId()); // validated on save, so present
        graph.addEdge(input.id(), a.id());
        if (seen.putIfAbsent(input.id(), input) == null) {
          todo.add(input);
        }
      }
    }
    return new Lineage(root, seen, graph);
  }
}
