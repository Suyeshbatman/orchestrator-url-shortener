package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import com.acme.orchestrator.core.model.Run;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What an agent sees: run metadata, read-only access to upstream artifacts, feedback from earlier
 * attempts, and tools. There is no way to change run state from here; results go back to the
 * coordinator as an {@link AgentResult}.
 *
 * <p>Every artifact read is recorded and becomes the {@code inputs} (lineage) of what the stage
 * produces, so lineage can't be forgotten or faked.
 */
public final class StageContext {

  private final Run run;
  private final String stageKey;
  private final int attempt;
  private final List<String> feedback;
  private final ArtifactStore artifacts;
  private final ToolRegistry tools;
  private final Set<ArtifactRef> reads = new LinkedHashSet<>();

  StageContext(
      Run run,
      String stageKey,
      int attempt,
      List<String> feedback,
      ArtifactStore artifacts,
      ToolRegistry tools) {
    this.run = run;
    this.stageKey = stageKey;
    this.attempt = attempt;
    this.feedback = List.copyOf(feedback);
    this.artifacts = artifacts;
    this.tools = tools;
  }

  public Run run() {
    return run;
  }

  public String stageKey() {
    return stageKey;
  }

  /** 1 for the first attempt. */
  public int attempt() {
    return attempt;
  }

  /** Why earlier attempts failed or were rejected, oldest first. Empty on a first attempt. */
  public List<String> feedback() {
    return feedback;
  }

  public ToolRegistry tools() {
    return tools;
  }

  /** Latest version of {@code type} in this run, from any stage. Recorded as an input. */
  public Optional<Artifact> artifact(String type) {
    Optional<Artifact> found = artifacts.latestOfType(run.id(), type);
    found.ifPresent(a -> record(a.ref()));
    return found;
  }

  /** Like {@link #artifact} but required. */
  public Artifact requireArtifact(String type) {
    return artifact(type)
        .orElseThrow(() -> new IllegalStateException("no upstream artifact of type " + type));
  }

  synchronized List<ArtifactRef> reads() {
    return new ArrayList<>(reads);
  }

  private synchronized void record(ArtifactRef ref) {
    reads.add(ref);
  }
}
