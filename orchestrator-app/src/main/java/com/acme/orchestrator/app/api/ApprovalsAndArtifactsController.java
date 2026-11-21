package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.api.ApiKeys.Principal;
import com.acme.orchestrator.app.api.ApiKeys.Role;
import com.acme.orchestrator.app.api.ApiModels.ApprovalView;
import com.acme.orchestrator.app.api.ApiModels.ArtifactSummary;
import com.acme.orchestrator.app.api.ApiModels.ArtifactView;
import com.acme.orchestrator.app.api.ApiModels.DecisionRequest;
import com.acme.orchestrator.app.api.ApiModels.Edge;
import com.acme.orchestrator.app.api.ApiModels.LineageView;
import com.acme.orchestrator.app.persistence.ApprovalRepository;
import com.acme.orchestrator.app.persistence.ApprovalRepository.ApprovalRow;
import com.acme.orchestrator.app.persistence.RunRepository;
import com.acme.orchestrator.app.run.RunService;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.artifact.Lineage;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Human checkpoints and the artifacts (with lineage) the runs produced. */
@RestController
class ApprovalsAndArtifactsController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RunService service;
  private final ApprovalRepository approvals;
  private final RunRepository runs;
  private final ArtifactStore artifacts;

  ApprovalsAndArtifactsController(
      RunService service,
      ApprovalRepository approvals,
      RunRepository runs,
      ArtifactStore artifacts) {
    this.service = service;
    this.approvals = approvals;
    this.runs = runs;
    this.artifacts = artifacts;
  }

  // ---- approvals ------------------------------------------------------------------------------

  @GetMapping("/api/approvals")
  List<ApprovalView> approvals(
      @RequestParam Optional<String> status, @RequestParam Optional<String> runId) {
    return approvals.list(status.map(s -> s.toUpperCase(Locale.ROOT)), runId).stream()
        .map(ApprovalsAndArtifactsController::view)
        .toList();
  }

  @PostMapping("/api/approvals/{id}")
  @RequiresRole(Role.APPROVER)
  ResponseEntity<ApprovalView> decide(
      @PathVariable String id,
      @Valid @RequestBody DecisionRequest request,
      @RequestAttribute(ApiKeyInterceptor.PRINCIPAL) Principal by) {
    ApprovalRow approval = service.decide(id, request.decision(), request.comment(), by);
    return ResponseEntity.accepted().body(view(approval));
  }

  private static ApprovalView view(ApprovalRow a) {
    return new ApprovalView(
        a.id(),
        a.runId(),
        a.stageId(),
        a.status(),
        a.reason(),
        a.summary(),
        a.requestedAt(),
        a.decidedAt(),
        a.decidedBy(),
        a.comment());
  }

  // ---- artifacts ------------------------------------------------------------------------------

  @GetMapping("/api/runs/{id}/artifacts")
  List<ArtifactSummary> runArtifacts(@PathVariable String id) {
    if (runs.find(id).isEmpty()) {
      throw ApiException.notFound(ProblemTypes.RUN_NOT_FOUND, "no run " + id);
    }
    return artifacts.byRun(id).stream().map(ApprovalsAndArtifactsController::summary).toList();
  }

  @GetMapping("/api/artifacts/{id}")
  ArtifactView artifact(@PathVariable String id) {
    Artifact a = find(id);
    return new ArtifactView(summary(a), JSON.readTree(a.contentJson()), a.rationale());
  }

  @GetMapping("/api/artifacts/{id}/lineage")
  LineageView lineage(@PathVariable String id) {
    find(id);
    Lineage lineage = artifacts.lineage(id);
    List<Edge> edges = new ArrayList<>();
    for (Artifact a : lineage.artifacts().values()) {
      for (ArtifactRef in : a.inputs()) {
        edges.add(new Edge(in.artifactId(), a.id()));
      }
    }
    return new LineageView(
        id,
        lineage.artifacts().values().stream()
            .map(ApprovalsAndArtifactsController::summary)
            .toList(),
        edges);
  }

  private Artifact find(String id) {
    return artifacts
        .get(id)
        .orElseThrow(
            () -> ApiException.notFound(ProblemTypes.ARTIFACT_NOT_FOUND, "no artifact " + id));
  }

  private static ArtifactSummary summary(Artifact a) {
    return new ArtifactSummary(
        a.id(),
        a.stageId(),
        a.type(),
        a.version(),
        a.contentHash(),
        a.producedBy().agent(),
        a.producedBy().model().orElse(null),
        a.producedBy().promptHash().orElse(null),
        a.inputs().stream().map(ArtifactRef::artifactId).toList(),
        a.createdAt());
  }
}
