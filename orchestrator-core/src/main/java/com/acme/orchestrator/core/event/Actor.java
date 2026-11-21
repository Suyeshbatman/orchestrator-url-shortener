package com.acme.orchestrator.core.event;

import java.util.regex.Pattern;

/** Who caused an event: {@code agent:<name>}, {@code human:<id>} or {@code system}. */
public sealed interface Actor {

  Pattern ID = Pattern.compile("[A-Za-z0-9._@-]{1,128}");

  record Agent(String name) implements Actor {
    public Agent {
      requireId(name);
    }

    @Override
    public String toString() {
      return "agent:" + name;
    }
  }

  record Human(String id) implements Actor {
    public Human {
      requireId(id);
    }

    @Override
    public String toString() {
      return "human:" + id;
    }
  }

  /** The orchestrator itself (scheduling, gates, budgets). */
  record SystemActor() implements Actor {
    @Override
    public String toString() {
      return "system";
    }
  }

  Actor SYSTEM = new SystemActor();

  static Actor agent(String name) {
    return new Agent(name);
  }

  static Actor human(String id) {
    return new Human(id);
  }

  /** Inverse of {@code toString()}. */
  static Actor parse(String text) {
    if ("system".equals(text)) {
      return SYSTEM;
    }
    if (text != null && text.startsWith("agent:")) {
      return new Agent(text.substring("agent:".length()));
    }
    if (text != null && text.startsWith("human:")) {
      return new Human(text.substring("human:".length()));
    }
    throw new IllegalArgumentException("actor must be agent:<name>, human:<id> or system: " + text);
  }

  private static void requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new IllegalArgumentException("actor id must match " + ID.pattern() + ": " + id);
    }
  }
}
