package com.acme.orchestrator.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tools an agent may use (workspace files, git, build runner, LLM gateway…), looked up by type.
 * Phase 5 registers the real ones; every tool call goes through policy there.
 */
public final class ToolRegistry {

  public static final ToolRegistry EMPTY = new ToolRegistry(Map.of());

  private final Map<Class<?>, Object> tools;

  private ToolRegistry(Map<Class<?>, Object> tools) {
    this.tools = Map.copyOf(tools);
  }

  public static Builder builder() {
    return new Builder();
  }

  public <T> Optional<T> find(Class<T> type) {
    return Optional.ofNullable(tools.get(type)).map(type::cast);
  }

  public <T> T require(Class<T> type) {
    return find(type)
        .orElseThrow(() -> new IllegalStateException("tool not available: " + type.getName()));
  }

  public static final class Builder {
    private final Map<Class<?>, Object> tools = new LinkedHashMap<>();

    public <T> Builder register(Class<T> type, T tool) {
      tools.put(type, type.cast(tool));
      return this;
    }

    public ToolRegistry build() {
      return new ToolRegistry(tools);
    }
  }
}
