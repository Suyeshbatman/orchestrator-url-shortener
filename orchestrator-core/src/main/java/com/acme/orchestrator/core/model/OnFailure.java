package com.acme.orchestrator.core.model;

import java.util.Optional;

/**
 * Loop-back on failure: re-run the upstream stage {@code loopTo} with this stage's output as
 * feedback, at most {@code maxLoops} times, then escalate to a human.
 *
 * @param feedback artifact type passed back to {@code loopTo}, e.g. {@code validation_report}
 */
public record OnFailure(String loopTo, int maxLoops, Optional<String> feedback) {

  public OnFailure {
    if (loopTo == null || loopTo.isBlank()) {
      throw new IllegalArgumentException("loopTo is required");
    }
    if (maxLoops < 1) {
      throw new IllegalArgumentException("maxLoops must be at least 1");
    }
    feedback = feedback == null ? Optional.empty() : feedback;
  }
}
