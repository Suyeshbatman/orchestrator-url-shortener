package com.acme.orchestrator.core.checkpoint;

/**
 * Port: saves and restores the run's workspace. The Git implementation (Phase 5) commits on the
 * agent branch and keeps a ref {@code refs/orch/<runId>/<label>}; {@link InMemoryCheckpointManager}
 * is the test double.
 */
public interface CheckpointManager {

  /** For runs without a workspace: checkpoints are recorded but there is nothing to restore. */
  CheckpointManager NONE =
      new CheckpointManager() {
        @Override
        public CheckpointRef create(String runId, String label) {
          return new CheckpointRef("none", runId, label);
        }

        @Override
        public void restore(CheckpointRef ref) {}
      };

  CheckpointRef create(String runId, String label);

  /** Puts the workspace back exactly as it was when {@code ref} was created. */
  void restore(CheckpointRef ref);

  /**
   * Puts only {@code paths} back as they were when {@code ref} was created; a path that didn't
   * exist then is deleted. For one of several tasks writing the same workspace in parallel: a full
   * restore would also undo the others' work. The default restores everything (right when nothing
   * else writes the workspace at the same time).
   */
  default void restorePaths(CheckpointRef ref, java.util.Collection<String> paths) {
    restore(ref);
  }
}
