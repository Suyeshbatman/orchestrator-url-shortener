package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.budget.Usage;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.model.ArtifactRef;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Messages to the coordinator. Workers and humans only ever send these; the coordinator thread is
 * the only thing that changes run state (ADR-0006).
 */
public sealed interface Command {

  /**
   * An agent finished an attempt. {@code dispatch} identifies the attempt (the seq of its
   * STAGE_STARTED event), so a late result from a halted or timed-out attempt is never mistaken for
   * the current one. {@code reads} becomes the lineage of what it produced; {@code checks} are its
   * policy checks; {@code reportedRisk} its own risk assessment, if any.
   */
  record StageCompleted(
      String stageKey,
      long dispatch,
      AgentResult.Success result,
      List<ArtifactRef> reads,
      List<PolicyCheck> checks,
      OptionalInt reportedRisk,
      Usage usage)
      implements Command {
    public StageCompleted {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(result, "result");
      reads = List.copyOf(reads);
      checks = List.copyOf(checks);
      Objects.requireNonNull(reportedRisk, "reportedRisk");
      Objects.requireNonNull(usage, "usage");
    }
  }

  /** An attempt failed: the agent said so, threw, or timed out. */
  record StageFailed(
      String stageKey,
      long dispatch,
      String reason,
      FailureKind kind,
      List<PolicyCheck> checks,
      Usage usage)
      implements Command {
    public StageFailed {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(kind, "kind");
      checks = List.copyOf(checks);
      Objects.requireNonNull(usage, "usage");
    }
  }

  /**
   * A human's verdict on a stage waiting for approval. Only counts when {@code approver} is a human
   * and {@code role} is {@link #APPROVER}; the app authenticates both before sending this.
   */
  record ApprovalDecision(
      String stageKey, Actor approver, String role, Decision decision, String comment)
      implements Command {

    public static final String APPROVER = "APPROVER";

    public enum Decision {
      APPROVE,
      REJECT
    }

    public ApprovalDecision {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(approver, "approver");
      role = role == null ? "" : role;
      Objects.requireNonNull(decision, "decision");
      comment = comment == null ? "" : comment;
    }
  }

  /** A changed requirement mid-run. Recorded now; re-planning arrives in Step 4.5. */
  record AmendRequirement(String text, Actor by) implements Command {
    public AmendRequirement {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(by, "by");
    }
  }

  /**
   * Safe stop: dispatch nothing more, let running attempts finish within the grace period (then
   * cancel them), take a checkpoint, halt. Resume continues.
   */
  record Halt(String reason, Actor by) implements Command {
    public Halt {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(by, "by");
    }
  }

  /** Kill switch: a safe stop without the grace period (running attempts are cancelled now). */
  record Kill(String reason, Actor by) implements Command {
    public Kill {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(by, "by");
    }
  }

  /**
   * Restore the checkpoint taken before {@code stageId} and mark it and everything downstream
   * ROLLED_BACK; they run again (on resume, if the run is halted).
   */
  record Rollback(String stageId, String reason, Actor by) implements Command {
    public Rollback {
      Objects.requireNonNull(stageId, "stageId");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(by, "by");
    }
  }

  /** Continue a halted run: halted stages run again. */
  record Resume(Actor by) implements Command {
    public Resume {
      Objects.requireNonNull(by, "by");
    }
  }

  /**
   * Internal: an approval's timeout elapsed. {@code request} is the seq of its APPROVAL_REQUESTED,
   * so a timer from an earlier request can't halt a later one.
   */
  record ApprovalTimeout(String stageKey, long request) implements Command {
    public ApprovalTimeout {
      Objects.requireNonNull(stageKey, "stageKey");
    }
  }

  /**
   * Internal: a retry's backoff elapsed. {@code retry} is the seq of its STAGE_RETRIED, so a timer
   * from before a halt can't start an attempt later.
   */
  record RetryDue(String stageKey, long retry) implements Command {
    public RetryDue {
      Objects.requireNonNull(stageKey, "stageKey");
    }
  }

  /** Internal: a safe stop's grace period ended ({@code request}: its SAFE_STOP_REQUESTED seq). */
  record GraceExpired(long request) implements Command {}

  /** Internal: the wall-clock budget may have run out. */
  record BudgetCheck() implements Command {}

  /** Internal: run a scheduling pass (sent once at start). */
  record Wake() implements Command {}
}
