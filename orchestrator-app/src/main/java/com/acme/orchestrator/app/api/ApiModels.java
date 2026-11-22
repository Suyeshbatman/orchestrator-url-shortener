package com.acme.orchestrator.app.api;

import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.RunMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the orchestration API. */
public final class ApiModels {

  private ApiModels() {}

  // ---- requests ---------------------------------------------------------------------------------

  /**
   * Exactly one of {@code scenarioId} (uses scenarios/&lt;id&gt;/requirement.md) or {@code
   * requirementText}.
   */
  public record CreateRunRequest(
      @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String scenarioId,
      @Size(max = 20_000) String requirementText,
      @NotNull RunMode mode,
      AutonomyLevel autonomyLevel,
      // a workflow file in the workflows directory, without .yml; default sdlc-standard
      @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String workflow) {}

  public record AnswersRequest(@NotEmpty @Valid List<Answer> answers) {}

  public record Answer(
      @NotBlank @Pattern(regexp = "AMB-[0-9]+") String ambiguityId,
      @NotBlank @Size(max = 4_000) String answer) {}

  public record AmendRequest(@NotBlank @Size(max = 20_000) String text) {}

  public record HaltRequest(@Size(max = 500) String reason) {}

  public record RollbackRequest(@NotBlank String toStage, @Size(max = 500) String reason) {}

  public record DecisionRequest(@NotNull Decision decision, @Size(max = 4_000) String comment) {}

  // ---- responses --------------------------------------------------------------------------------

  public record RunSummary(
      String id,
      String workflowId,
      String scenarioId,
      String mode,
      String autonomyLevel,
      String status,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {}

  public record StageNode(
      String id,
      String executor,
      String status,
      int attempt,
      int loopCount,
      Instant startedAt,
      Instant endedAt,
      boolean approvalRequired) {}

  public record Edge(String from, String to) {}

  public record RunView(RunSummary run, List<StageNode> nodes, List<Edge> edges) {}

  public record EventView(
      long seq,
      String type,
      String stageId,
      String actor,
      Map<String, Object> payload,
      Instant timestamp,
      String prevHash,
      String hash) {}

  public record AuditView(
      String runId,
      boolean valid,
      int eventCount,
      Long firstBrokenSeq,
      String problem,
      String headHash) {}

  public record ArtifactSummary(
      String id,
      String stageId,
      String type,
      int version,
      String contentHash,
      String producedBy,
      String model,
      String promptHash,
      List<String> inputs,
      Instant createdAt) {}

  public record ArtifactView(ArtifactSummary artifact, Object content, String rationale) {}

  public record LineageView(String root, List<ArtifactSummary> nodes, List<Edge> edges) {}

  public record ApprovalView(
      String id,
      String runId,
      String stageId,
      String status,
      String reason,
      Map<String, Object> summary,
      Instant requestedAt,
      Instant decidedAt,
      String decidedBy,
      String comment) {}

  public record Accepted(String runId, String command) {}
}
