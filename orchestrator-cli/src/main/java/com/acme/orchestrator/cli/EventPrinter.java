package com.acme.orchestrator.cli;

import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/** Prints a run's notable events as they happen (polling the store once a second). */
final class EventPrinter {

  private static final Set<EventType> QUIET =
      Set.of(
          EventType.STAGE_READY,
          EventType.AGENT_INVOKED,
          EventType.GATE_EVALUATED,
          EventType.CHECKPOINT_CREATED);

  private final EventStore events;
  private final String runId;
  private final PrintStream out;
  private final Thread thread;
  private volatile boolean running = true;
  private long printed;

  EventPrinter(EventStore events, String runId, PrintStream out) {
    this.events = events;
    this.runId = runId;
    this.out = out;
    this.thread = Thread.ofVirtual().unstarted(this::loop);
  }

  void start() {
    thread.start();
  }

  void stopAndFlush() throws InterruptedException {
    running = false;
    thread.join(5_000);
    flush();
  }

  private void loop() {
    while (running) {
      flush();
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private synchronized void flush() {
    List<Event> log = events.events(runId);
    for (Event e : log) {
      if (e.seq() <= printed) {
        continue;
      }
      printed = e.seq();
      if (QUIET.contains(e.type())) {
        continue;
      }
      String detail =
          e.payloadString("reason")
              .or(() -> e.payloadString("summary"))
              .or(() -> e.payloadString("action"))
              .orElse("");
      out.printf(
          "  %4d %-20s %-18s %s%n",
          e.seq(),
          e.type(),
          e.stageId().orElse(""),
          detail.length() > 140 ? detail.substring(0, 140) + "…" : detail);
    }
  }
}
