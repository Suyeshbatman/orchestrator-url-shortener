package com.acme.orchestrator.core.event;

import java.util.Optional;

/**
 * Result of verifying a run's chain.
 *
 * @param firstBrokenSeq the first seq that fails a check; empty when the chain is intact
 * @param headHash hash of the last event as stored (anchor it externally to detect truncation)
 */
public record AuditReport(
    String runId,
    int eventCount,
    Optional<Long> firstBrokenSeq,
    Optional<Problem> problem,
    String headHash) {

  public enum Problem {
    /** The stored hash isn't SHA-256(prevHash + canonical body): the event was altered. */
    HASH_MISMATCH,
    /** prevHash doesn't equal the previous event's hash: an event was removed or reordered. */
    BROKEN_LINK,
    /** seq isn't the previous seq + 1 (or 1 for the first). */
    SEQUENCE_GAP,
    /** The event belongs to another run. */
    WRONG_RUN
  }

  public boolean valid() {
    return firstBrokenSeq.isEmpty();
  }

  static AuditReport intact(String runId, int count, String headHash) {
    return new AuditReport(runId, count, Optional.empty(), Optional.empty(), headHash);
  }

  static AuditReport broken(String runId, int count, long seq, Problem problem, String headHash) {
    return new AuditReport(runId, count, Optional.of(seq), Optional.of(problem), headHash);
  }
}
