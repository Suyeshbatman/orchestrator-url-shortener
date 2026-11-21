package com.acme.orchestrator.core.budget;

/** LLM consumption: calls made and tokens used (input + output). */
public record Usage(long llmCalls, long tokens) {

  public static final Usage NONE = new Usage(0, 0);

  public Usage {
    if (llmCalls < 0 || tokens < 0) {
      throw new IllegalArgumentException("usage must not be negative");
    }
  }

  public Usage plus(Usage other) {
    return new Usage(llmCalls + other.llmCalls, tokens + other.tokens);
  }
}
