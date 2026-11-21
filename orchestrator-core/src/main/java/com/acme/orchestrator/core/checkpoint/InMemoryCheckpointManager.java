package com.acme.orchestrator.core.checkpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A workspace of files in memory, with snapshot checkpoints. Thread-safe. For tests and the demo;
 * the real workspace is a Git worktree (Phase 5).
 */
public final class InMemoryCheckpointManager implements CheckpointManager {

  private final Map<String, String> files = new TreeMap<>();
  private final Map<String, Map<String, String>> snapshots = new LinkedHashMap<>();
  private final List<CheckpointRef> restored = new ArrayList<>();

  public synchronized void write(String path, String content) {
    files.put(path, content);
  }

  public synchronized Optional<String> read(String path) {
    return Optional.ofNullable(files.get(path));
  }

  /** A copy of the workspace. */
  public synchronized Map<String, String> files() {
    return new TreeMap<>(files);
  }

  @Override
  public synchronized CheckpointRef create(String runId, String label) {
    CheckpointRef ref = new CheckpointRef("cp-" + (snapshots.size() + 1), runId, label);
    snapshots.put(ref.id(), new TreeMap<>(files));
    return ref;
  }

  /**
   * @throws IllegalArgumentException for a checkpoint this manager didn't create
   */
  @Override
  public synchronized void restore(CheckpointRef ref) {
    Map<String, String> snapshot = snapshots.get(ref.id());
    if (snapshot == null) {
      throw new IllegalArgumentException("unknown checkpoint " + ref.id());
    }
    files.clear();
    files.putAll(snapshot);
    restored.add(ref);
  }

  /** Checkpoints restored so far, in order. */
  public synchronized List<CheckpointRef> restored() {
    return List.copyOf(restored);
  }

  public synchronized int created() {
    return snapshots.size();
  }
}
