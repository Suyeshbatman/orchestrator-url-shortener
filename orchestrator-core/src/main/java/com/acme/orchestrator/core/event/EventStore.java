package com.acme.orchestrator.core.event;

import java.util.List;
import java.util.Optional;

/**
 * Port: append-only, per-run, hash-chained event log. Implementations must append atomically (seq
 * and prevHash taken from the current head) and never update or delete.
 */
public interface EventStore {

  /** Appends to the draft's run, assigning the next seq, the timestamp and the chain hash. */
  Event append(EventDraft draft);

  /** The run's events in seq order; empty for an unknown run. */
  List<Event> events(String runId);

  /** The latest event of the run. */
  Optional<Event> head(String runId);

  /** Every run with at least one event, in creation order. */
  List<String> runIds();
}
