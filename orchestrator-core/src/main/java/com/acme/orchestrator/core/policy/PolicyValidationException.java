package com.acme.orchestrator.core.policy;

import java.util.List;

/** Policy or autonomy config that can't be used. Lists every problem, each with its location. */
public final class PolicyValidationException extends RuntimeException {

  private final List<String> problems;

  public PolicyValidationException(List<String> problems) {
    super("invalid policy config:\n  - " + String.join("\n  - ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
