package com.acme.orchestrator.core.model;

import java.util.List;

/** A workflow file that can't be used. Lists every problem found, each with its location. */
public final class WorkflowValidationException extends RuntimeException {

  private final List<String> problems;

  public WorkflowValidationException(String source, List<String> problems) {
    super("invalid workflow " + source + ":\n  - " + String.join("\n  - ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
