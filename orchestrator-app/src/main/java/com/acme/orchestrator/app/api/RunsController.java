package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.api.ApiKeys.Principal;
import com.acme.orchestrator.app.api.ApiKeys.Role;
import com.acme.orchestrator.app.api.ApiModels.Accepted;
import com.acme.orchestrator.app.api.ApiModels.AmendRequest;
import com.acme.orchestrator.app.api.ApiModels.AnswersRequest;
import com.acme.orchestrator.app.api.ApiModels.AuditView;
import com.acme.orchestrator.app.api.ApiModels.CreateRunRequest;
import com.acme.orchestrator.app.api.ApiModels.EventView;
import com.acme.orchestrator.app.api.ApiModels.HaltRequest;
import com.acme.orchestrator.app.api.ApiModels.RollbackRequest;
import com.acme.orchestrator.app.api.ApiModels.RunSummary;
import com.acme.orchestrator.app.api.ApiModels.RunView;
import com.acme.orchestrator.app.run.RunService;
import com.acme.orchestrator.core.event.CanonicalJson;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs: start, inspect (DAG, events, audit) and steer (answers, amendments, halt, resume,
 * rollback).
 */
@RestController
@RequestMapping("/api/runs")
class RunsController {

  private final RunService runs;

  RunsController(RunService runs) {
    this.runs = runs;
  }

  @PostMapping
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<RunView> create(
      @Valid @RequestBody CreateRunRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    RunView view = runs.create(request, by);
    return ResponseEntity.created(URI.create("/api/runs/" + view.run().id())).body(view);
  }

  @GetMapping
  List<RunSummary> list() {
    return runs.list();
  }

  @GetMapping("/{id}")
  RunView get(@PathVariable String id) {
    return runs.view(id);
  }

  @GetMapping(value = "/{id}/dag.mmd", produces = MediaType.TEXT_PLAIN_VALUE)
  String dag(@PathVariable String id) {
    return runs.mermaid(id);
  }

  @GetMapping("/{id}/events")
  List<EventView> events(@PathVariable String id, @RequestParam(defaultValue = "0") long afterSeq) {
    return runs.events(id, afterSeq).stream()
        .map(e ->
            new EventView(
                e.seq(),
                e.type().name(),
                e.stageId().orElse(null),
                e.actor().toString(),
                e.payload(),
                e.timestamp(),
                e.prevHash(),
                e.hash()))
        .toList();
  }

  @GetMapping("/{id}/audit/verify")
  AuditView audit(@PathVariable String id) {
    return runs.audit(id);
  }

  @PostMapping("/{id}/answers")
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<Accepted> answers(
      @PathVariable String id,
      @Valid @RequestBody AnswersRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    String json =
        CanonicalJson.write(
            Map.of(
                "answers",
                request.answers().stream()
                    .map(a -> Map.of("ambiguityId", a.ambiguityId(), "answer", a.answer()))
                    .toList()));
    runs.answer(id, json, by);
    return ResponseEntity.accepted().body(new Accepted(id, "answers"));
  }

  @PostMapping("/{id}/requirements")
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<Accepted> amend(
      @PathVariable String id,
      @Valid @RequestBody AmendRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    runs.amend(id, request.text(), by);
    return ResponseEntity.accepted().body(new Accepted(id, "amend"));
  }

  @PostMapping("/{id}/halt")
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<Accepted> halt(
      @PathVariable String id,
      @Valid @RequestBody(required = false) HaltRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    runs.halt(id, request == null ? null : request.reason(), by);
    return ResponseEntity.accepted().body(new Accepted(id, "halt"));
  }

  @PostMapping("/{id}/resume")
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<Accepted> resume(
      @PathVariable String id, @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    runs.resume(id, by);
    return ResponseEntity.accepted().body(new Accepted(id, "resume"));
  }

  @PostMapping("/{id}/rollback")
  @RequiresRole(Role.OPERATOR)
  ResponseEntity<Accepted> rollback(
      @PathVariable String id,
      @Valid @RequestBody RollbackRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    runs.rollback(id, request.toStage(), request.reason(), by);
    return ResponseEntity.accepted().body(new Accepted(id, "rollback"));
  }
}
