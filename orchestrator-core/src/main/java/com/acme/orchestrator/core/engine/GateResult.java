package com.acme.orchestrator.core.engine;

import java.util.List;

/**
 * A gate's verdict, with reasons (why it failed, or what it checked).
 *
 * @param needsInput the gate failed because a human must decide something (e.g. answer the spec's
 *     blocking questions): the stage waits for a human instead of retrying
 */
public record GateResult(boolean passed, List<String> reasons, boolean needsInput) {

  public GateResult {
    reasons = List.copyOf(reasons);
    if (passed && needsInput) {
      throw new IllegalArgumentException("a gate that passed needs no input");
    }
  }

  public GateResult(boolean passed, List<String> reasons) {
    this(passed, reasons, false);
  }

  /** Failed until a human answers or decides; retrying the agent alone can't fix it. */
  public static GateResult needsInput(List<String> reasons) {
    return new GateResult(false, reasons, true);
  }

  public static GateResult pass(String... reasons) {
    return new GateResult(true, List.of(reasons));
  }

  public static GateResult fail(String... reasons) {
    return new GateResult(false, List.of(reasons));
  }
}
