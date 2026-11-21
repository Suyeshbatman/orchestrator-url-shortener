package com.acme.orchestrator.core.event;

import java.util.List;

/**
 * Recomputes a run's hash chain from the first event and reports the first broken seq.
 *
 * <p>Detects any altered, removed, inserted or reordered event. It can't detect events cut off the
 * end (the remaining chain is still valid); that needs the head hash anchored outside the database
 * (limitation L-6).
 */
public final class AuditVerifier {

  private final EventStore events;

  public AuditVerifier(EventStore events) {
    this.events = events;
  }

  public AuditReport verify(String runId) {
    return verify(runId, events.events(runId));
  }

  /** Verifies an explicit list (e.g. an export), which must be the whole run in stored order. */
  public static AuditReport verify(String runId, List<Event> log) {
    String expectedPrev = Event.GENESIS;
    long expectedSeq = 1;
    String head = log.isEmpty() ? Event.GENESIS : log.getLast().hash();
    for (Event e : log) {
      AuditReport.Problem problem = null;
      if (!e.runId().equals(runId)) {
        problem = AuditReport.Problem.WRONG_RUN;
      } else if (e.seq() != expectedSeq) {
        problem = AuditReport.Problem.SEQUENCE_GAP;
      } else if (!e.prevHash().equals(expectedPrev)) {
        problem = AuditReport.Problem.BROKEN_LINK;
      } else if (!e.hashMatches()) {
        problem = AuditReport.Problem.HASH_MISMATCH;
      }
      if (problem != null) {
        return AuditReport.broken(runId, log.size(), e.seq(), problem, head);
      }
      expectedPrev = e.hash();
      expectedSeq++;
    }
    return AuditReport.intact(runId, log.size(), head);
  }
}
