package com.acme.orchestrator.core.artifact;

import com.acme.orchestrator.core.model.Artifact;
import java.util.List;
import java.util.Optional;

/**
 * Port: immutable, versioned artifacts. Saving the same (run, stage, type) again creates version
 * n+1; nothing is overwritten, so re-planning keeps v1 in history (DESIGN_RATIONALE §4.7).
 */
public interface ArtifactStore {

  /**
   * Stores the next version. Every input must be a stored artifact whose hash matches the ref.
   *
   * @throws IllegalArgumentException for an unknown or mismatched input
   */
  Artifact save(ArtifactDraft draft);

  Optional<Artifact> get(String artifactId);

  /** Latest version of {@code type} produced by {@code stageId} in the run. */
  Optional<Artifact> latest(String runId, String stageId, String type);

  /** Latest version of {@code type} from any stage of the run (how stages read upstream work). */
  Optional<Artifact> latestOfType(String runId, String type);

  /** All versions, oldest first. */
  List<Artifact> versions(String runId, String stageId, String type);

  /** Everything the run produced, in save order. */
  List<Artifact> byRun(String runId);

  /**
   * The artifact and all its ancestors via recorded inputs.
   *
   * @throws IllegalArgumentException for an unknown artifact
   */
  Lineage lineage(String artifactId);
}
