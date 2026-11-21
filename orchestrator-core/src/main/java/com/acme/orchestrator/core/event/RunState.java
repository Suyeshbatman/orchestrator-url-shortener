package com.acme.orchestrator.core.event;

import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A run as rebuilt from its events.
 *
 * @param stages by {@link StageInstance#key()}, in first-seen order. Stages with no events yet are
 *     absent, which means PENDING (the same convention as {@code Dag.readyNodes})
 * @param lastSeq seq of the last applied event; the coordinator resumes appending after it
 */
public record RunState(Run run, Map<String, StageInstance> stages, long lastSeq, String headHash) {

  public RunState {
    stages = Collections.unmodifiableMap(new LinkedHashMap<>(stages));
  }

  /** Status per stage key, for {@code Dag.readyNodes}. */
  public Map<String, StageStatus> statuses() {
    Map<String, StageStatus> statuses = new LinkedHashMap<>();
    stages.forEach((key, s) -> statuses.put(key, s.status()));
    return statuses;
  }
}
