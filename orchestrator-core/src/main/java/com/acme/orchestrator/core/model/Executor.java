package com.acme.orchestrator.core.model;

/** Who does a stage's work: an agent, or a built-in deterministic action. Exactly one per stage. */
public sealed interface Executor {

  /** e.g. {@code RequirementsAgent} */
  record Agent(String name) implements Executor {
    public Agent {
      requireName(name, "agent");
    }
  }

  /** e.g. {@code git.mergeToMain} */
  record Action(String name) implements Executor {
    public Action {
      requireName(name, "action");
    }
  }

  String name();

  private static void requireName(String name, String kind) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(kind + " name is required");
    }
  }
}
