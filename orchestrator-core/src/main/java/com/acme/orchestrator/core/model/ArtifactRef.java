package com.acme.orchestrator.core.model;

import java.util.Objects;

/** Points at an exact artifact version; the hash makes lineage tamper-evident. */
public record ArtifactRef(String artifactId, String type, int version, String contentHash) {

  public ArtifactRef {
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentHash, "contentHash");
    if (version < 1) {
      throw new IllegalArgumentException("version starts at 1");
    }
  }
}
