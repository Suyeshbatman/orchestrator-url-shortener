package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.budget.BudgetExceededException;
import com.acme.orchestrator.core.budget.BudgetGuard;
import com.acme.orchestrator.core.budget.Usage;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What an agent sees: run metadata, read-only access to upstream artifacts, feedback from earlier
 * attempts, tools, and the policy engine. There is no way to change run state from here; results go
 * back to the coordinator as an {@link AgentResult}.
 *
 * <p>Every artifact read is recorded and becomes the {@code inputs} (lineage) of what the stage
 * produces, so lineage can't be forgotten or faked. Every {@link #authorize} call is recorded too
 * and lands in the audit log as POLICY_EVALUATED, whatever the agent then does.
 */
public final class StageContext {

  private final Run run;
  private final String stageKey;
  private final int attempt;
  private final List<String> feedback;
  private final ArtifactStore artifacts;
  private final ToolRegistry tools;
  private final PolicyEngine policies;
  private final Optional<String> fallbackStrategy;
  private final BudgetGuard budget;
  private final Optional<TaskExpansion.Task> task;
  private final WriteClaims claims; // null outside a fan-out
  private Usage usage = Usage.NONE;
  private final Set<ArtifactRef> reads = new LinkedHashSet<>();
  private final List<PolicyCheck> checks = new ArrayList<>();
  private Integer reportedRisk;

  StageContext(
      Run run,
      String stageKey,
      int attempt,
      List<String> feedback,
      ArtifactStore artifacts,
      ToolRegistry tools,
      PolicyEngine policies,
      Optional<String> fallbackStrategy,
      BudgetGuard budget) {
    this(
        run,
        stageKey,
        attempt,
        feedback,
        artifacts,
        tools,
        policies,
        fallbackStrategy,
        budget,
        Optional.empty(),
        null);
  }

  StageContext(
      Run run,
      String stageKey,
      int attempt,
      List<String> feedback,
      ArtifactStore artifacts,
      ToolRegistry tools,
      PolicyEngine policies,
      Optional<String> fallbackStrategy,
      BudgetGuard budget,
      Optional<TaskExpansion.Task> task,
      WriteClaims claims) {
    this.task = task;
    this.claims = claims;
    this.run = run;
    this.stageKey = stageKey;
    this.attempt = attempt;
    this.feedback = List.copyOf(feedback);
    this.artifacts = artifacts;
    this.tools = tools;
    this.policies = policies;
    this.fallbackStrategy = fallbackStrategy;
    this.budget = budget;
  }

  /**
   * A context for running an agent outside a coordinator (agent tests, tooling). Nothing it does is
   * recorded in a run's log; within a run the coordinator creates the context.
   */
  public static StageContext standalone(
      Run run,
      String stageKey,
      int attempt,
      List<String> feedback,
      ArtifactStore artifacts,
      ToolRegistry tools,
      PolicyEngine policies) {
    return standalone(
        run, stageKey, attempt, feedback, artifacts, tools, policies, Optional.empty());
  }

  /** As {@link #standalone}, for a fallback attempt with {@code fallbackStrategy}. */
  public static StageContext standalone(
      Run run,
      String stageKey,
      int attempt,
      List<String> feedback,
      ArtifactStore artifacts,
      ToolRegistry tools,
      PolicyEngine policies,
      Optional<String> fallbackStrategy) {
    return new StageContext(
        run,
        stageKey,
        attempt,
        feedback,
        artifacts,
        tools,
        policies,
        fallbackStrategy,
        new BudgetGuard(run.budgets(), Usage.NONE));
  }

  /**
   * As {@link #standalone}, for one task of a fanned-out stage (agent tests). No write conflicts
   * are tracked.
   */
  public static StageContext standaloneTask(
      Run run,
      String stageKey,
      TaskExpansion.Task task,
      ArtifactStore artifacts,
      ToolRegistry tools,
      PolicyEngine policies) {
    return new StageContext(
        run,
        stageKey,
        1,
        List.of(),
        artifacts,
        tools,
        policies,
        Optional.empty(),
        new BudgetGuard(run.budgets(), Usage.NONE),
        Optional.of(task),
        null);
  }

  public Run run() {
    return run;
  }

  /**
   * The task this attempt implements, when its stage fanned out ({@code expand: tasks}): only it,
   * with the rest of the task graph as context. Empty for a stage that runs once for all tasks.
   */
  public Optional<TaskExpansion.Task> task() {
    return task;
  }

  public String stageKey() {
    return stageKey;
  }

  /** 1 for the first attempt. */
  public int attempt() {
    return attempt;
  }

  /** Why earlier attempts failed or were rejected, oldest first. Empty on a first attempt. */
  public List<String> feedback() {
    return feedback;
  }

  /**
   * Set on the fallback attempt, after retries are used up: the stage's {@code fallback.strategy}
   * (e.g. {@code minimal-patch}), telling the agent to try a narrower approach.
   */
  public Optional<String> fallbackStrategy() {
    return fallbackStrategy;
  }

  public ToolRegistry tools() {
    return tools;
  }

  /** Latest version of {@code type} in this run, from any stage. Recorded as an input. */
  public Optional<Artifact> artifact(String type) {
    Optional<Artifact> found = artifacts.latestOfType(run.id(), type);
    found.ifPresent(a -> record(a.ref()));
    return found;
  }

  /** Like {@link #artifact} but required. */
  public Artifact requireArtifact(String type) {
    return artifact(type)
        .orElseThrow(() -> new IllegalStateException("no upstream artifact of type " + type));
  }

  /**
   * Asks the policy engine about an action, at this run's autonomy level. The agent must not do a
   * DENY'd action, and any DENY fails the attempt. REQUIRE_APPROVAL lets the agent go on (its
   * writes stay on the run's own branch), but the stage then waits for a human before it can
   * succeed.
   */
  public Decision authorize(ProposedAction action) {
    Decision decision = policies.evaluate(action, run.autonomyLevel());
    if (claims != null && action instanceof ProposedAction.FileWrite write && !decision.denied()) {
      claims.claim(stageKey, write.path()); // before recording: a refused write restores nothing
    }
    synchronized (this) {
      checks.add(new PolicyCheck(action, decision));
    }
    return decision;
  }

  /**
   * The agent's own risk assessment of its work, 0–100 (e.g. the design from its risk register).
   * The stage's risk is the highest of this and its policy checks; at L2 a design below 30 skips
   * approval. Without either, the risk is unknown and nothing is relaxed.
   */
  public synchronized void reportRisk(int score) {
    if (score < 0 || score > 100) {
      throw new IllegalArgumentException("risk score must be 0-100, was " + score);
    }
    reportedRisk = reportedRisk == null ? score : Math.max(reportedRisk, score);
  }

  /**
   * Call before each LLM call (the LLM gateway does).
   *
   * @throws BudgetExceededException if the run has no LLM calls or tokens left; the attempt then
   *     ends and the run is safe-stopped
   */
  public void beforeLlmCall() {
    budget.beforeLlmCall();
  }

  /** Call after each LLM call: charges the run's budget and is recorded for the audit log. */
  public void recordLlmCall(long inputTokens, long outputTokens) {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }
    Usage call = new Usage(1, inputTokens + outputTokens);
    budget.charge(call);
    synchronized (this) {
      usage = usage.plus(call);
    }
  }

  synchronized Usage usage() {
    return usage;
  }

  synchronized List<ArtifactRef> reads() {
    return new ArrayList<>(reads);
  }

  synchronized List<PolicyCheck> checks() {
    return List.copyOf(checks);
  }

  synchronized OptionalInt reportedRisk() {
    return reportedRisk == null ? OptionalInt.empty() : OptionalInt.of(reportedRisk);
  }

  private synchronized void record(ArtifactRef ref) {
    reads.add(Objects.requireNonNull(ref));
  }
}
