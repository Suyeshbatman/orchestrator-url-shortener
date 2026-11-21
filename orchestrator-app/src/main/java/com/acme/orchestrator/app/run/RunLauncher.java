package com.acme.orchestrator.app.run;

import com.acme.orchestrator.app.persistence.RunRepository.RunRow;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.model.Run;

/**
 * Starts the coordinator of a run: a new one, or one that continues an existing log (the
 * coordinator then recovers: attempts that were running are retried, timers re-armed).
 */
@FunctionalInterface
public interface RunLauncher {

  Coordinator launch(RunRow row, Run run);
}
