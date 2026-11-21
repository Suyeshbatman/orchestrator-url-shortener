package com.acme.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A human checkpoint on a stage: after its exit gates pass, it waits for an APPROVER.
 *
 * @param timeout how long to wait before the run is halted; empty (the default) waits forever
 */
public record Approval(boolean required, String reason, Optional<Duration> timeout) {

  public static final Approval NONE = new Approval(false, "");

  public Approval {
    reason = reason == null ? "" : reason;
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (required && reason.isBlank()) {
      throw new IllegalArgumentException("a required approval needs a reason");
    }
    if (timeout.isPresent() && (timeout.get().isNegative() || timeout.get().isZero())) {
      throw new IllegalArgumentException("an approval timeout must be positive");
    }
  }

  /** No timeout. */
  public Approval(boolean required, String reason) {
    this(required, reason, Optional.empty());
  }
}
