package com.acme.orchestrator.core.event;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link EventStore} in memory, for tests and the CLI demo. Thread-safe. */
public final class InMemoryEventStore implements EventStore {

  private final Clock clock;
  private final Map<String, List<Event>> runs = new LinkedHashMap<>();

  public InMemoryEventStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized Event append(EventDraft draft) {
    List<Event> log = runs.computeIfAbsent(draft.runId(), k -> new ArrayList<>());
    Event previous = log.isEmpty() ? null : log.getLast();
    Event event =
        Event.chain(
            previous == null ? 1 : previous.seq() + 1,
            draft.runId(),
            draft.type(),
            draft.stageId(),
            draft.actor(),
            draft.payload(),
            clock.instant(),
            previous == null ? Event.GENESIS : previous.hash());
    log.add(event);
    return event;
  }

  @Override
  public synchronized List<Event> events(String runId) {
    return List.copyOf(runs.getOrDefault(runId, List.of()));
  }

  @Override
  public synchronized Optional<Event> head(String runId) {
    List<Event> log = runs.get(runId);
    return log == null || log.isEmpty() ? Optional.empty() : Optional.of(log.getLast());
  }

  @Override
  public synchronized List<String> runIds() {
    return List.copyOf(runs.keySet());
  }

  @Override
  public Clock clock() {
    return clock;
  }
}
