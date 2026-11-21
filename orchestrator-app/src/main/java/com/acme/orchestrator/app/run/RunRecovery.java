package com.acme.orchestrator.app.run;

import com.acme.orchestrator.app.persistence.RunRepository;
import com.acme.orchestrator.app.persistence.RunRepository.RunRow;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.Run;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * At startup, gives every unfinished run a coordinator again. Each rebuilds its state from the log;
 * attempts that were running when the process stopped count as transient failures (retried while
 * attempts remain), pending retries and approval timeouts are re-armed, a safe stop that was under
 * way finishes. A run that can't be resumed (e.g. its policies changed) is logged and left alone.
 */
public final class RunRecovery implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RunRecovery.class);

  private final RunRepository runs;
  private final EventStore events;
  private final RunLauncher launcher;
  private final boolean enabled;

  public RunRecovery(RunRepository runs, EventStore events, RunLauncher launcher, boolean enabled) {
    this.runs = runs;
    this.events = events;
    this.launcher = launcher;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (enabled) {
      List<String> recovered = recoverAll();
      LOG.info("recovered {} unfinished run(s): {}", recovered.size(), recovered);
    }
  }

  /**
   * @return the ids of the runs that got a coordinator again
   */
  public List<String> recoverAll() {
    List<String> recovered = new ArrayList<>();
    for (RunRow row : runs.unfinished()) {
      List<Event> log = events.events(row.id());
      if (log.isEmpty()) {
        continue; // registered but never started
      }
      try {
        Run run = RunStateProjector.project(log).run();
        launcher.launch(row, run);
        recovered.add(row.id());
      } catch (RuntimeException e) {
        LOG.error("could not resume run {}: {}", row.id(), e.getMessage(), e);
      }
    }
    return recovered;
  }
}
