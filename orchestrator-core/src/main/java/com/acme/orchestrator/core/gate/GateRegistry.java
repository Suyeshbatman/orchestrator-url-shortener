package com.acme.orchestrator.core.gate;

import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.GateEvaluator;
import com.acme.orchestrator.core.engine.GateResult;
import com.acme.orchestrator.core.model.GateCatalog;
import com.acme.orchestrator.core.model.GateRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Named gates (gate name → {@link Gate}). As a {@link GateCatalog} it rejects unknown gates and bad
 * arguments when a workflow is loaded; as a {@link GateEvaluator} the coordinator runs gates
 * through it (each evaluation is recorded as GATE_EVALUATED by the coordinator).
 */
public final class GateRegistry implements GateEvaluator, GateCatalog {

  private final Map<String, Gate> gates;

  private GateRegistry(Map<String, Gate> gates) {
    this.gates = Map.copyOf(gates);
  }

  /** The gates used by {@code sdlc-standard.yml}. */
  public static GateRegistry standard(SchemaValidator schemas) {
    return StandardGates.register(builder(), schemas).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Set<String> names() {
    return new TreeSet<>(gates.keySet());
  }

  @Override
  public void check(GateRef gate) {
    gate(gate.name())
        .orElseThrow(() ->
            new IllegalArgumentException(
                "unknown gate '" + gate.name() + "' (known: " + String.join(", ", names()) + ")"))
        .checkArgument(gate.argument());
  }

  /** An unknown gate fails rather than throws: the run stops at it with a clear reason. */
  @Override
  public GateResult evaluate(GateRef gate, GateContext context) {
    Optional<Gate> found = gate(gate.name());
    if (found.isEmpty()) {
      return GateResult.fail("unknown gate '" + gate.name() + "'");
    }
    return Objects.requireNonNull(
        found.get().evaluate(gate.argument(), context), "gate " + gate + " returned null");
  }

  private Optional<Gate> gate(String name) {
    return Optional.ofNullable(gates.get(name));
  }

  public static final class Builder {
    private final Map<String, Gate> gates = new LinkedHashMap<>();

    private Builder() {}

    /**
     * @throws IllegalArgumentException if the name is malformed or already registered
     */
    public Builder register(String name, Gate gate) {
      new GateRef(name, Optional.empty()); // validates the name
      Objects.requireNonNull(gate, "gate");
      if (gates.putIfAbsent(name, gate) != null) {
        throw new IllegalArgumentException("gate already registered: " + name);
      }
      return this;
    }

    public GateRegistry build() {
      return new GateRegistry(gates);
    }
  }
}
