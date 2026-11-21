package com.acme.orchestrator.core.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A gate named in a workflow, e.g. {@code artifact.schemaValid:requirements_spec} or {@code
 * coverage.min:0.80}. The name selects the gate implementation; the argument is passed to it.
 */
public record GateRef(String name, Optional<String> argument) {

  private static final Pattern NAME =
      Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)+");

  public GateRef {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "gate name must look like 'area.check' (letters/digits, dot-separated): " + name);
    }
    argument = argument == null ? Optional.empty() : argument;
    if (argument.isPresent() && argument.get().isBlank()) {
      throw new IllegalArgumentException("gate argument must not be blank: " + name);
    }
  }

  /** Parses {@code name} or {@code name:argument}. */
  public static GateRef parse(String spec) {
    if (spec == null) {
      throw new IllegalArgumentException("gate must not be null");
    }
    int colon = spec.indexOf(':');
    return colon < 0
        ? new GateRef(spec.strip(), Optional.empty())
        : new GateRef(
            spec.substring(0, colon).strip(), Optional.of(spec.substring(colon + 1).strip()));
  }

  @Override
  public String toString() {
    return argument.map(a -> name + ":" + a).orElse(name);
  }
}
