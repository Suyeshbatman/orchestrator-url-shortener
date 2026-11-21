package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The agents of {@code sdlc-standard.yml}, by the names the workflow uses. The {@code
 * git.mergeToMain} action is not an agent and is added by the app (Phase 6).
 */
public final class SdlcAgents {

  private SdlcAgents() {}

  public static Map<String, Agent> standard(
      AgentKit kit, PolicyEngine policies, ValidationAgent.Settings validation) {
    Map<String, Agent> agents = new LinkedHashMap<>();
    agents.put(RequirementsAgent.NAME, new RequirementsAgent(kit));
    agents.put(DecompositionAgent.NAME, new DecompositionAgent(kit));
    agents.put(ImpactAnalysisAgent.NAME, new ImpactAnalysisAgent(kit));
    agents.put(DesignAgent.NAME, new DesignAgent(kit));
    agents.put(ImplementationAgent.NAME, new ImplementationAgent(kit));
    agents.put(TestAuthoringAgent.NAME, new TestAuthoringAgent(kit));
    agents.put(DocsAgent.NAME, new DocsAgent(kit));
    agents.put(ValidationAgent.NAME, new ValidationAgent(policies, validation));
    agents.put(ReleaseAgent.NAME, new ReleaseAgent(kit));
    return Map.copyOf(agents);
  }
}
