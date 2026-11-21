package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.metrics.ReliabilitySummary;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reliability figures over every run in the log (live counters are on /actuator/prometheus). */
@RestController
class MetricsController {

  private final EventStore events;

  MetricsController(EventStore events) {
    this.events = events;
  }

  @GetMapping("/api/metrics/summary")
  ReliabilitySummary summary() {
    List<List<Event>> logs = events.runIds().stream().map(events::events).toList();
    return ReliabilitySummary.compute(logs);
  }
}
