package com.acme.orchestrator.core.artifact;

import com.acme.orchestrator.core.model.ArtifactRef;
import com.acme.orchestrator.core.model.ProducedBy;
import java.util.List;
import java.util.Objects;

/** An artifact to store; the store assigns id, version, hash and timestamp. */
public record ArtifactDraft(
    String runId,
    String stageId,
    String type,
    String contentJson,
    ProducedBy producedBy,
    List<ArtifactRef> inputs,
    String rationale) {

  public ArtifactDraft {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentJson, "contentJson");
    Objects.requireNonNull(producedBy, "producedBy");
    inputs = List.copyOf(inputs);
    rationale = rationale == null ? "" : rationale;
  }
}
