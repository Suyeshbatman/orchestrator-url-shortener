package com.acme.orchestrator.cli;

import com.acme.orchestrator.cli.Scenario.ScriptedDecision;
import com.acme.orchestrator.core.engine.Command;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Plays the humans of a scenario: answers blocking questions from {@code answers.json} (as the
 * operator), decides approvals as the script says (as the approver; unlisted ones are approved),
 * and amends the requirement once the scripted stage has been approved. Each approval request is
 * acted on once.
 */
final class ScriptedHuman {

  private final Scenario scenario;
  private final Consumer<Command> submit;
  private final Actor approver;
  private final Actor operator;
  private final Consumer<String> say;
  private final Set<Long> handled = new HashSet<>();
  private final Map<String, Integer> decided = new HashMap<>();
  private boolean answered;
  private boolean amended;

  ScriptedHuman(
      Scenario scenario,
      Consumer<Command> submit,
      Actor approver,
      Actor operator,
      Consumer<String> say) {
    this.scenario = scenario;
    this.submit = submit;
    this.approver = approver;
    this.operator = operator;
    this.say = say;
  }

  /** Acts on whatever is waiting; returns whether anything was submitted. */
  boolean act(RunState state, List<Event> log) {
    if (scenario.amend().isPresent()
        && !amended
        && approved(log, scenario.amend().get().afterApprovalOf())) {
      amended = true;
      say.accept("amending the requirement: " + scenario.amend().get().text());
      submit.accept(new Command.AmendRequirement(scenario.amend().get().text(), operator));
      return true;
    }
    boolean acted = false;
    for (StageInstance stage : state.stages().values()) {
      if (stage.status() != StageStatus.WAITING_APPROVAL) {
        continue;
      }
      Event request = lastRequest(log, stage.key());
      if (request == null || !handled.add(request.seq())) {
        continue;
      }
      acted = true;
      if (Boolean.TRUE.equals(request.payload().get("needsInput"))
          && scenario.answersJson().isPresent()
          && !answered) {
        answered = true;
        say.accept(stage.key() + " has open questions: answering from answers.json");
        submit.accept(new Command.ProvideAnswers(scenario.answersJson().get(), operator));
        continue;
      }
      ScriptedDecision d = next(stage.key());
      say.accept(
          d.decision()
              + " "
              + stage.key()
              + (d.comment().isBlank() ? "" : " (\"" + d.comment() + "\")")
              + " -- "
              + request.payloadString("reason").orElse(""));
      submit.accept(
          new ApprovalDecision(
              stage.key(), approver, ApprovalDecision.APPROVER, d.decision(), d.comment()));
    }
    return acted;
  }

  /** The scripted decision for this stage's next request, or an approval. */
  private ScriptedDecision next(String stage) {
    int n = decided.merge(stage, 1, Integer::sum);
    List<ScriptedDecision> forStage =
        scenario.decisions().stream().filter(d -> d.stage().equals(stage)).toList();
    return n <= forStage.size()
        ? forStage.get(n - 1)
        : new ScriptedDecision(stage, Decision.APPROVE, "");
  }

  private static Event lastRequest(List<Event> log, String stage) {
    Event last = null;
    for (Event e : log) {
      if (e.type() == EventType.APPROVAL_REQUESTED
          && e.stageId().filter(stage::equals).isPresent()) {
        last = e;
      }
    }
    return last;
  }

  private static boolean approved(List<Event> log, String stage) {
    return log.stream()
        .anyMatch(e ->
            e.type() == EventType.APPROVAL_GRANTED
                && e.stageId().filter(stage::equals).isPresent());
  }
}
