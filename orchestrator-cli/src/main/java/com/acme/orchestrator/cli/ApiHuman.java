package com.acme.orchestrator.cli;

import com.acme.orchestrator.cli.Scenario.ScriptedDecision;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Plays a scenario's humans against a running orchestrator, over its API: the operator (bob)
 * answers blocking questions and amends the requirement, and the approver (alice) decides each
 * approval as {@code scenario.yml} says, approving everything it doesn't mention. Each approval's
 * summary is printed, the way a person would read it before deciding.
 */
final class ApiHuman {

  static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED", "HALTED", "ROLLED_BACK");

  private final Scenario scenario;
  private final String runId;
  private final OrchestratorApi operator;
  private final OrchestratorApi approver;
  private final Consumer<String> say;
  private final Set<String> handled = new HashSet<>();
  private final Map<String, Integer> decided = new HashMap<>();
  private boolean answered;
  private boolean amended;
  private String status = "";

  ApiHuman(
      Scenario scenario,
      String runId,
      OrchestratorApi operator,
      OrchestratorApi approver,
      Consumer<String> say) {
    this.scenario = scenario;
    this.runId = runId;
    this.operator = operator;
    this.approver = approver;
    this.say = say;
  }

  String status() {
    return status;
  }

  /** One look at the run; acts if it waits for a human. True once it has finished. */
  boolean step() throws IOException, InterruptedException {
    status = operator.get("/api/runs/" + runId).path("run").path("status").asString();
    if (FINISHED.contains(status)) {
      return true;
    }
    if (!status.equals("AWAITING_HUMAN")) {
      return false;
    }
    JsonNode approvals = operator.get("/api/approvals?runId=" + runId);
    if (scenario.amend().isPresent()
        && !amended
        && granted(approvals, scenario.amend().get().afterApprovalOf())) {
      amended = true;
      say.accept("bob amends the requirement: " + scenario.amend().get().text());
      operator.post(
          "/api/runs/" + runId + "/requirements", Map.of("text", scenario.amend().get().text()));
      return false;
    }
    for (JsonNode a : approvals) {
      if (!a.path("status").asString().equals("PENDING") || !handled.add(a.path("id").asString())) {
        continue;
      }
      String stage = a.path("stageId").asString();
      String reason = a.path("reason").asString();
      if (reason.startsWith("human input needed")
          && scenario.answersJson().isPresent()
          && !answered) {
        answered = true;
        say.accept(stage + " has blocking questions: bob answers from answers.json");
        operator.post("/api/runs/" + runId + "/answers", scenario.answersJson().get());
        continue;
      }
      ScriptedDecision d = next(stage);
      say.accept(describe(stage, reason, a.path("summary")));
      say.accept(
          "  alice: " + d.decision() + (d.comment().isBlank() ? "" : " (\"" + d.comment() + "\")"));
      approver.post(
          "/api/approvals/" + a.path("id").asString(),
          Map.of("decision", d.decision().name(), "comment", d.comment()));
    }
    return false;
  }

  /** What the approver reads: stage, why it waits, and the facts in the approval summary. */
  static String describe(String stage, String reason, JsonNode summary) {
    StringBuilder text =
        new StringBuilder("approval: ").append(stage).append(" -- ").append(reason);
    if (summary.has("summary")) {
      text.append("\n    ").append(abbreviate(summary.path("summary").asString(), 160));
    }
    if (summary.has("riskScore")) {
      text.append("\n    risk ").append(summary.path("riskScore").asInt());
    }
    JsonNode diff = summary.path("diffStats");
    if (diff.isObject()) {
      text.append(", diff ")
          .append(diff.path("filesChanged").asInt())
          .append(" files / ")
          .append(diff.path("linesChanged").asInt())
          .append(" lines");
    }
    return text.toString();
  }

  private ScriptedDecision next(String stage) {
    int n = decided.merge(stage, 1, Integer::sum);
    List<ScriptedDecision> forStage =
        scenario.decisions().stream().filter(d -> d.stage().equals(stage)).toList();
    return n <= forStage.size()
        ? forStage.get(n - 1)
        : new ScriptedDecision(stage, Decision.APPROVE, "");
  }

  private static boolean granted(JsonNode approvals, String stage) {
    for (JsonNode a : approvals) {
      if (a.path("stageId").asString().equals(stage)
          && a.path("status").asString().equals("GRANTED")) {
        return true;
      }
    }
    return false;
  }

  private static String abbreviate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }
}
