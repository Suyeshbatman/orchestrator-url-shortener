package com.acme.orchestrator.core.policy;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Hashing;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Policies and autonomy levels as loaded at run start, with the SHA-256 of each file's text (line
 * endings normalized). The hashes go into RUN_CREATED, so a run is tied to the exact rules it
 * started under.
 */
public record PolicySet(
    List<Policy> policies,
    Map<AutonomyLevel, String> levelDescriptions,
    String policiesHash,
    String autonomyHash) {

  /** No policies; only the code invariants apply. */
  public static final PolicySet EMPTY =
      new PolicySet(
          List.of(),
          Arrays.stream(AutonomyLevel.values())
              .collect(Collectors.toMap(Function.identity(), AutonomyLevel::name)),
          Hashing.sha256Hex(""),
          Hashing.sha256Hex(""));

  public PolicySet {
    policies = List.copyOf(policies);
    levelDescriptions = Map.copyOf(levelDescriptions);
    Objects.requireNonNull(policiesHash, "policiesHash");
    Objects.requireNonNull(autonomyHash, "autonomyHash");
  }
}
