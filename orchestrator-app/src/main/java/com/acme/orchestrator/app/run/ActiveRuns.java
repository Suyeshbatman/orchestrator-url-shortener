package com.acme.orchestrator.app.run;

import com.acme.orchestrator.core.engine.Coordinator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The coordinators of the runs this process is driving, by run id. */
public final class ActiveRuns implements AutoCloseable {

  private final Map<String, Coordinator> coordinators = new ConcurrentHashMap<>();

  public void put(Coordinator coordinator) {
    Coordinator previous = coordinators.put(coordinator.runId(), coordinator);
    if (previous != null && previous != coordinator) {
      previous.close();
    }
  }

  public Optional<Coordinator> get(String runId) {
    return Optional.ofNullable(coordinators.get(runId));
  }

  public int size() {
    return coordinators.size();
  }

  /** On shutdown: stops every loop; the logs stay as they are and the next start recovers them. */
  @Override
  public void close() {
    coordinators.values().forEach(Coordinator::close);
    coordinators.clear();
  }
}
