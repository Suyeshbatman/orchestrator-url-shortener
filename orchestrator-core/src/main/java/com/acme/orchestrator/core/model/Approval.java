package com.acme.orchestrator.core.model;

/** A human checkpoint on a stage: after its exit gates pass, it waits for an APPROVER. */
public record Approval(boolean required, String reason) {

  public static final Approval NONE = new Approval(false, "");

  public Approval {
    reason = reason == null ? "" : reason;
    if (required && reason.isBlank()) {
      throw new IllegalArgumentException("a required approval needs a reason");
    }
  }
}
