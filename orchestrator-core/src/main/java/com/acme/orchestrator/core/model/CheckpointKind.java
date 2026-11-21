package com.acme.orchestrator.core.model;

/** What is snapshotted before a stage runs, so it can be rolled back. */
public enum CheckpointKind {
  NONE,
  /** A git commit/tag in the agent workspace. */
  GIT
}
