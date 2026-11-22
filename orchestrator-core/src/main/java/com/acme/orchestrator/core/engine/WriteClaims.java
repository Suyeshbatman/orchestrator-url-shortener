package com.acme.orchestrator.core.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Which sub-stage of a fan-out wrote which file, so parallel tasks never silently overwrite each
 * other. Tasks whose estimated files overlap are chained already; this catches a task that writes a
 * file it didn't announce. A write fails the attempt ({@link AgentValidationException}, so it is
 * retried) when another task is still writing that file, or wrote it after this attempt started
 * (this attempt planned its change from the older content). The retry starts from the newer file.
 *
 * <p>Thread-safe: agents claim from their worker threads, the coordinator begins and settles.
 */
final class WriteClaims {

  private record Claim(String stageKey, long tick, boolean live) {}

  private final Map<String, Claim> byPath = new HashMap<>();
  private final Map<String, Long> started = new HashMap<>();
  private long tick;

  /** A new attempt of {@code stageKey}: its earlier claims are dropped (it claims again). */
  synchronized void begin(String stageKey) {
    release(stageKey);
    started.put(stageKey, ++tick);
  }

  /**
   * @throws AgentValidationException when another task holds or newly wrote {@code path}
   */
  synchronized void claim(String stageKey, String path) {
    Claim existing = byPath.get(path);
    long start = started.getOrDefault(stageKey, 0L);
    if (existing != null
        && !existing.stageKey().equals(stageKey)
        && (existing.live() || existing.tick() > start)) {
      throw new AgentValidationException(
          "write conflict: "
              + path
              + " was also written by "
              + existing.stageKey()
              + " while this task ran; retrying from the current file");
    }
    byPath.put(path, new Claim(stageKey, ++tick, true));
  }

  /** The attempt ended: its writes stand, but no longer block others' new attempts. */
  synchronized void settle(String stageKey) {
    byPath.replaceAll((path, c) ->
        c.stageKey().equals(stageKey) && c.live() ? new Claim(c.stageKey(), c.tick(), false) : c);
  }

  /** The attempt's writes were undone (its checkpoint restored). */
  synchronized void release(String stageKey) {
    byPath.values().removeIf(c -> c.stageKey().equals(stageKey));
  }
}
