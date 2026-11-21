package com.acme.orchestrator.core.engine;

import java.util.List;

/** A gate's verdict, with reasons (why it failed, or what it checked). */
public record GateResult(boolean passed, List<String> reasons) {

  public GateResult {
    reasons = List.copyOf(reasons);
  }

  public static GateResult pass(String... reasons) {
    return new GateResult(true, List.of(reasons));
  }

  public static GateResult fail(String... reasons) {
    return new GateResult(false, List.of(reasons));
  }
}
