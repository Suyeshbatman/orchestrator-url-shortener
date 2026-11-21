package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.llm.JsonSchemas;
import com.acme.orchestrator.agents.llm.LlmClient;
import com.acme.orchestrator.agents.llm.LlmGateway;
import com.acme.orchestrator.agents.llm.LlmRequest;
import com.acme.orchestrator.agents.llm.LlmResponse;
import com.acme.orchestrator.agents.llm.PromptTemplates;
import com.acme.orchestrator.agents.llm.RecordingLlmGateway;
import com.acme.orchestrator.agents.llm.ReplayLlmGateway;
import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Runs one agent outside the engine: upstream artifacts in an in-memory store, the LLM either
 * scripted (recorded to a replay directory as it answers) or replayed from that directory.
 */
final class AgentHarness {

  static final WorkflowDefinition STANDARD =
      new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));
  static final JsonSchemas SCHEMAS = new JsonSchemas();

  final InMemoryArtifactStore artifacts =
      new InMemoryArtifactStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  final Run run;
  final List<LlmRequest> sent = new ArrayList<>();

  AgentHarness(RunMode mode) {
    run = Run.create("run-1", STANDARD, mode, AutonomyLevel.L1_SUPERVISED);
  }

  /** A canned reply from {@code fixtures/<name>.json}. */
  static String fixture(String name) {
    try (InputStream in = AgentHarness.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Artifact input(String stageId, String type, String json) {
    return artifacts.save(
        new ArtifactDraft(
            "run-1", stageId, type, json, ProducedBy.deterministic("test"), List.of(), "fixture"));
  }

  StageContext context(String stage, List<String> feedback, ToolRegistry tools) {
    return context(stage, feedback, tools, PolicyEngine.withoutPolicies(), Optional.empty());
  }

  StageContext context(
      String stage,
      List<String> feedback,
      ToolRegistry tools,
      PolicyEngine policies,
      Optional<String> fallback) {
    return StageContext.standalone(
        run, stage, feedback.size() + 1, feedback, artifacts, tools, policies, fallback);
  }

  StageContext context(String stage) {
    return context(stage, List.of(), ToolRegistry.EMPTY);
  }

  /** The model answers with {@code replies} in order; every answer is recorded in {@code dir}. */
  AgentKit recording(Path dir, String... replies) {
    Deque<String> script = new ArrayDeque<>(List.of(replies));
    LlmGateway scripted =
        request -> {
          sent.add(request);
          return new LlmResponse(
              script.removeFirst(),
              Optional.empty(),
              1_200,
              400,
              "claude-sonnet-5-5",
              Duration.ofMillis(900));
        };
    return kit(new RecordingLlmGateway(scripted, dir));
  }

  /** No model: only what {@code dir} holds. */
  AgentKit replay(Path dir) {
    return kit(new ReplayLlmGateway(dir));
  }

  private static AgentKit kit(LlmGateway gateway) {
    return new AgentKit(new LlmClient(gateway, SCHEMAS), new PromptTemplates(), AgentKit.SHORTENER);
  }
}
