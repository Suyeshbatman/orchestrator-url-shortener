package com.acme.orchestrator.app.run;

import com.acme.orchestrator.agents.llm.JsonSchemas;
import com.acme.orchestrator.agents.llm.LlmClient;
import com.acme.orchestrator.agents.llm.LlmGateway;
import com.acme.orchestrator.agents.llm.LlmGateways;
import com.acme.orchestrator.agents.llm.PromptTemplates;
import com.acme.orchestrator.agents.llm.SecretRedactor;
import com.acme.orchestrator.agents.sdlc.AgentKit;
import com.acme.orchestrator.agents.sdlc.SdlcAgents;
import com.acme.orchestrator.agents.sdlc.ValidationAgent;
import com.acme.orchestrator.agents.tools.GitCheckpointManager;
import com.acme.orchestrator.agents.tools.MergeToMainAction;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.agents.tools.WorkspaceManager;
import com.acme.orchestrator.app.persistence.RunRepository.RunRow;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentRegistry;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * The production wiring of a run: its workspace, the LLM gateway (mode from the environment; replay
 * fixtures from the scenario, or from {@code data-dir/runs/<id>/replay}), the nine SDLC agents plus
 * the publish action, the real gates, policies and Git checkpoints.
 */
public final class StandardRunLauncher implements RunLauncher {

  private final OrchestratorProperties props;
  private final EventStore events;
  private final ArtifactStore artifacts;
  private final PolicyEngine policies;
  private final GateRegistry gates;
  private final JsonSchemas schemas;
  private final WorkspaceManager workspaces;
  private final Map<String, String> environment;
  private final Clock clock;
  private final ActiveRuns active;

  public StandardRunLauncher(
      OrchestratorProperties props,
      EventStore events,
      ArtifactStore artifacts,
      PolicyEngine policies,
      GateRegistry gates,
      JsonSchemas schemas,
      WorkspaceManager workspaces,
      Map<String, String> environment,
      Clock clock,
      ActiveRuns active) {
    this.props = props;
    this.events = events;
    this.artifacts = artifacts;
    this.policies = policies;
    this.gates = gates;
    this.schemas = schemas;
    this.workspaces = workspaces;
    this.environment = environment;
    this.clock = clock;
    this.active = active;
  }

  @Override
  public Coordinator launch(RunRow row, Run run) {
    WorkflowDefinition workflow =
        new WorkflowLoader(gates).load(props.workflowsDir().resolve(row.workflowFile()));
    Workspace workspace = workspaces.create(run.id());
    Path replay =
        row.scenarioId()
            .map(id -> props.scenariosDir().resolve(id).resolve("replay"))
            .orElse(props.dataDir().resolve("runs").resolve(run.id()).resolve("replay"));
    LlmGateway llm =
        LlmGateways.create(
            LlmGateways.Config.fromEnvironment(environment, replay),
            SecretRedactor.fromEnvironment(environment));
    AgentKit kit =
        new AgentKit(new LlmClient(llm, schemas), new PromptTemplates(), AgentKit.SHORTENER);

    Map<String, Agent> agents =
        new HashMap<>(SdlcAgents.standard(kit, policies, ValidationAgent.Settings.SHORTENER));
    agents.put(
        MergeToMainAction.NAME,
        new MergeToMainAction(workspaces::create, workspaces.baseBranch(), clock));

    Coordinator.Services services =
        new Coordinator.Services(
                events,
                artifacts,
                AgentRegistry.of(agents),
                gates,
                ToolRegistry.builder().register(Workspace.class, workspace).build(),
                policies)
            .withCheckpoints(new GitCheckpointManager(workspaces::create, clock))
            .withSafeStopGrace(props.safeStopGrace());
    Coordinator coordinator = Coordinator.start(workflow, run, services);
    active.put(coordinator);
    return coordinator;
  }
}
