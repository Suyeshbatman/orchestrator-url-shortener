package com.acme.orchestrator.core.model;

import static com.acme.orchestrator.core.model.StageEvent.APPROVE;
import static com.acme.orchestrator.core.model.StageEvent.APPROVE_START;
import static com.acme.orchestrator.core.model.StageEvent.AWAIT_APPROVAL;
import static com.acme.orchestrator.core.model.StageEvent.DEPENDENCIES_MET;
import static com.acme.orchestrator.core.model.StageEvent.FAIL;
import static com.acme.orchestrator.core.model.StageEvent.HALT;
import static com.acme.orchestrator.core.model.StageEvent.INVALIDATE;
import static com.acme.orchestrator.core.model.StageEvent.REJECT;
import static com.acme.orchestrator.core.model.StageEvent.RESET;
import static com.acme.orchestrator.core.model.StageEvent.ROLL_BACK;
import static com.acme.orchestrator.core.model.StageEvent.SCHEDULE_RETRY;
import static com.acme.orchestrator.core.model.StageEvent.SKIP;
import static com.acme.orchestrator.core.model.StageEvent.START;
import static com.acme.orchestrator.core.model.StageEvent.SUCCEED;
import static com.acme.orchestrator.core.model.StageStatus.FAILED;
import static com.acme.orchestrator.core.model.StageStatus.HALTED;
import static com.acme.orchestrator.core.model.StageStatus.INVALIDATED;
import static com.acme.orchestrator.core.model.StageStatus.PENDING;
import static com.acme.orchestrator.core.model.StageStatus.READY;
import static com.acme.orchestrator.core.model.StageStatus.RETRYING;
import static com.acme.orchestrator.core.model.StageStatus.ROLLED_BACK;
import static com.acme.orchestrator.core.model.StageStatus.RUNNING;
import static com.acme.orchestrator.core.model.StageStatus.SKIPPED;
import static com.acme.orchestrator.core.model.StageStatus.SUCCEEDED;
import static com.acme.orchestrator.core.model.StageStatus.WAITING_APPROVAL;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only place stage statuses change. Explicit table: anything not listed is illegal and throws,
 * so an engine bug surfaces as an exception instead of a silently corrupted run.
 *
 * <pre>
 * PENDING          DEPENDENCIES_MET→READY  SKIP→SKIPPED  HALT→HALTED
 * READY            START→RUNNING  SKIP→SKIPPED  FAIL→FAILED (entry gate)  INVALIDATE→INVALIDATED
 *                  HALT→HALTED  AWAIT_APPROVAL→WAITING_APPROVAL (approval before a publish runs)
 * RUNNING          SUCCEED→SUCCEEDED  AWAIT_APPROVAL→WAITING_APPROVAL  SCHEDULE_RETRY→RETRYING
 *                  FAIL→FAILED  INVALIDATE→INVALIDATED  HALT→HALTED
 * WAITING_APPROVAL APPROVE→SUCCEEDED  APPROVE_START→READY  REJECT→RETRYING
 *                  INVALIDATE→INVALIDATED  HALT→HALTED
 * RETRYING         START→RUNNING  FAIL→FAILED  HALT→HALTED
 * SUCCEEDED        INVALIDATE→INVALIDATED
 * FAILED           INVALIDATE→INVALIDATED
 * SKIPPED          INVALIDATE→INVALIDATED
 * INVALIDATED      RESET→PENDING
 * HALTED           RESET→PENDING   (resume)
 * ROLLED_BACK      RESET→PENDING   (it runs again)
 *
 * ROLL_BACK→ROLLED_BACK from every status that has started: READY, RUNNING, WAITING_APPROVAL,
 * RETRYING, SUCCEEDED, FAILED, SKIPPED, INVALIDATED, HALTED.
 * </pre>
 */
public final class StageStateMachine {

  private static final Map<StageStatus, Map<StageEvent, StageStatus>> TABLE = table();

  private StageStateMachine() {}

  /** The status after {@code event}, or {@link IllegalTransitionException}. */
  public static StageStatus transition(StageStatus from, StageEvent event) {
    return next(from, event).orElseThrow(() -> new IllegalTransitionException(from, event));
  }

  public static Optional<StageStatus> next(StageStatus from, StageEvent event) {
    return Optional.ofNullable(TABLE.get(from).get(event));
  }

  public static boolean canTransition(StageStatus from, StageEvent event) {
    return next(from, event).isPresent();
  }

  /** Allowed events from {@code from} and where each leads. */
  public static Map<StageEvent, StageStatus> allowed(StageStatus from) {
    return TABLE.get(from);
  }

  private static Map<StageStatus, Map<StageEvent, StageStatus>> table() {
    Map<StageStatus, Map<StageEvent, StageStatus>> t = new EnumMap<>(StageStatus.class);
    for (StageStatus s : StageStatus.values()) {
      t.put(s, new EnumMap<>(StageEvent.class));
    }
    t.get(PENDING).put(DEPENDENCIES_MET, READY);
    t.get(PENDING).put(SKIP, SKIPPED);
    t.get(PENDING).put(HALT, HALTED);

    t.get(READY).put(START, RUNNING);
    t.get(READY).put(SKIP, SKIPPED);
    t.get(READY).put(FAIL, FAILED); // an entry gate failed
    t.get(READY).put(INVALIDATE, INVALIDATED);
    t.get(READY).put(HALT, HALTED);
    t.get(READY).put(AWAIT_APPROVAL, WAITING_APPROVAL); // a publish asks before it runs

    t.get(RUNNING).put(SUCCEED, SUCCEEDED);
    t.get(RUNNING).put(AWAIT_APPROVAL, WAITING_APPROVAL);
    t.get(RUNNING).put(SCHEDULE_RETRY, RETRYING);
    t.get(RUNNING).put(FAIL, FAILED);
    t.get(RUNNING).put(INVALIDATE, INVALIDATED);
    t.get(RUNNING).put(HALT, HALTED);

    t.get(WAITING_APPROVAL).put(APPROVE, SUCCEEDED);
    t.get(WAITING_APPROVAL).put(APPROVE_START, READY); // approved before it ran
    t.get(WAITING_APPROVAL).put(REJECT, RETRYING); // re-run with the comment (or FAIL if spent)
    t.get(WAITING_APPROVAL).put(INVALIDATE, INVALIDATED);
    t.get(WAITING_APPROVAL).put(HALT, HALTED);

    t.get(RETRYING).put(START, RUNNING);
    t.get(RETRYING).put(FAIL, FAILED);
    t.get(RETRYING).put(HALT, HALTED);

    t.get(SUCCEEDED).put(INVALIDATE, INVALIDATED);
    t.get(SUCCEEDED).put(ROLL_BACK, ROLLED_BACK);

    t.get(FAILED).put(INVALIDATE, INVALIDATED);
    t.get(FAILED).put(ROLL_BACK, ROLLED_BACK);

    t.get(SKIPPED).put(INVALIDATE, INVALIDATED);

    t.get(INVALIDATED).put(RESET, PENDING);

    t.get(HALTED).put(RESET, PENDING);
    t.get(ROLLED_BACK).put(RESET, PENDING);
    for (StageStatus started :
        List.of(READY, RUNNING, WAITING_APPROVAL, RETRYING, SKIPPED, INVALIDATED, HALTED)) {
      t.get(started).put(ROLL_BACK, ROLLED_BACK); // SUCCEEDED and FAILED are listed above
    }

    t.replaceAll((status, events) -> Collections.unmodifiableMap(events));
    return Collections.unmodifiableMap(t);
  }
}
