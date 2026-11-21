package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.model.ArtifactRef;
import java.util.List;
import java.util.Objects;

/**
 * Messages to the coordinator. Workers and humans only ever send these; the coordinator thread is
 * the only thing that changes run state (ADR-0006).
 */
public sealed interface Command {

  /**
   * An agent finished an attempt. {@code dispatch} identifies the attempt (the seq of its
   * STAGE_STARTED event), so a late result from a halted or timed-out attempt is never mistaken for
   * the current one. {@code reads} becomes the lineage of what it produced.
   */
  record StageCompleted(
      String stageKey, long dispatch, AgentResult.Success result, List<ArtifactRef> reads)
      implements Command {
    public StageCompleted {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(result, "result");
      reads = List.copyOf(reads);
    }
  }

  /** An attempt failed: the agent said so, threw, or timed out. */
  record StageFailed(String stageKey, long dispatch, String reason) implements Command {
    public StageFailed {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record ApprovalDecision(String stageKey, Actor approver, Decision decision, String comment)
      implements Command {
    public enum Decision {
      APPROVE,
      REJECT
    }

    public ApprovalDecision {
      Objects.requireNonNull(stageKey, "stageKey");
      Objects.requireNonNull(approver, "approver");
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

  /** Safe stop: dispatch nothing more; results still in flight are ignored. */
  record Halt(String reason, Actor by) implements Command {
    public Halt {
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

  /** Internal: run a scheduling pass (sent once at start). */
  record Wake() implements Command {}
}
