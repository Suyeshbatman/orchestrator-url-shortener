package com.acme.orchestrator.core.model;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, versioned stage output (requirements spec, task graph, diff, report…). The content
 * hash is checked on construction, so an artifact whose content was altered can't be built.
 *
 * @param contentJson the content exactly as stored; {@code contentHash} is SHA-256 of it
 * @param inputs the exact artifact versions this one was derived from (lineage)
 * @param rationale why the producer made it this way (shown to approvers)
 */
public record Artifact(
    String id,
    String runId,
    String stageId,
    String type,
    int version,
    String contentJson,
    String contentHash,
    ProducedBy producedBy,
    List<ArtifactRef> inputs,
    String rationale,
    Instant createdAt) {

  public Artifact {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentJson, "contentJson");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(producedBy, "producedBy");
    inputs = List.copyOf(inputs);
    rationale = rationale == null ? "" : rationale;
    Objects.requireNonNull(createdAt, "createdAt");
    if (version < 1) {
      throw new IllegalArgumentException("version starts at 1");
    }
    if (!contentHash.equals(Hashing.sha256Hex(contentJson))) {
      throw new IllegalArgumentException("contentHash does not match contentJson for " + id);
    }
  }

  /** A new artifact with its hash computed and {@code createdAt} from {@code clock}. */
  public static Artifact create(
      String id,
      String runId,
      String stageId,
      String type,
      int version,
      String contentJson,
      ProducedBy producedBy,
      List<ArtifactRef> inputs,
      String rationale,
      Clock clock) {
    return new Artifact(
        id,
        runId,
        stageId,
        type,
        version,
        contentJson,
        Hashing.sha256Hex(contentJson),
        producedBy,
        inputs,
        rationale,
        clock.instant());
  }

  public ArtifactRef ref() {
    return new ArtifactRef(id, type, version, contentHash);
  }
}
