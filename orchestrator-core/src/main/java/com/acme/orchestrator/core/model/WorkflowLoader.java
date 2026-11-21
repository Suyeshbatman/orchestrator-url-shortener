package com.acme.orchestrator.core.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses and validates a workflow YAML (format: BUILD_PLAN §6.3). Strict: unknown keys are errors
 * (a typo like {@code dependOn} must not silently drop a dependency). All problems are collected
 * and reported together, each with its location.
 *
 * <p>Checks: required fields; unique stage ids; exactly one of {@code agent}/{@code action}; {@code
 * dependsOn} names existing stages, without self-references, duplicates or cycles; {@code
 * onFailure.loopTo} is an upstream stage; gate syntax, and that each gate is known to the {@link
 * GateCatalog}; durations; value ranges.
 */
public final class WorkflowLoader {

  static final Set<String> SUPPORTED_EXPANSIONS = Set.of("tasks");
  static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

  private static final Pattern STAGE_ID = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

  private static final Set<String> TOP_KEYS =
      Set.of("workflow", "version", "budgets", "defaults", "stages");
  private static final Set<String> BUDGET_KEYS = Set.of("maxWallClock", "maxLlmCalls", "maxTokens");
  private static final Set<String> DEFAULT_KEYS = Set.of("retry", "timeout");
  private static final Set<String> RETRY_KEYS =
      Set.of("maxAttempts", "backoff", "baseDelay", "maxDelay", "jitter");
  private static final Set<String> STAGE_KEYS =
      Set.of(
          "id",
          "agent",
          "action",
          "dependsOn",
          "condition",
          "entryGates",
          "exitGates",
          "approval",
          "retry",
          "fallback",
          "checkpoint",
          "onFailure",
          "expand",
          "highImpact",
          "timeout");
  private static final Set<String> APPROVAL_KEYS = Set.of("required", "reason", "timeout");
  private static final Set<String> FALLBACK_KEYS = Set.of("strategy");
  private static final Set<String> ON_FAILURE_KEYS = Set.of("loopTo", "maxLoops", "feedback");

  private final YAMLMapper yaml = YAMLMapper.builder().build();
  private final GateCatalog gateCatalog;

  /** Checks gate syntax only. Runs should use {@link #WorkflowLoader(GateCatalog)}. */
  public WorkflowLoader() {
    this(GateCatalog.ANY);
  }

  /** Every gate must also be known to {@code gateCatalog}, with an acceptable argument. */
  public WorkflowLoader(GateCatalog gateCatalog) {
    this.gateCatalog = Objects.requireNonNull(gateCatalog, "gateCatalog");
  }

  public WorkflowDefinition load(Path file) {
    try {
      return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read workflow " + file, e);
    }
  }

  /**
   * @param source shown in error messages (file name)
   * @throws WorkflowValidationException listing every problem
   */
  public WorkflowDefinition parse(String yamlText, String source) {
    JsonNode root;
    try {
      root = yaml.readTree(yamlText);
    } catch (JacksonException e) {
      throw new WorkflowValidationException(
          source, List.of("not valid YAML: " + e.getOriginalMessage()));
    }
    Problems problems = new Problems();
    if (root == null || !root.isObject()) {
      problems.add("", "the document must be a mapping");
      throw problems.exception(source);
    }

    problems.unknownKeys("", root, TOP_KEYS);
    String id = string(root, "workflow", "", problems, true);
    int version = integer(root, "version", "", problems, 1, 1, Integer.MAX_VALUE);
    Budgets budgets = budgets(root.get("budgets"), problems);

    JsonNode defaults = root.get("defaults");
    RetryPolicy defaultRetry = RetryPolicy.NONE;
    Duration defaultTimeout = DEFAULT_TIMEOUT;
    if (defaults != null) {
      if (problems.requireObject("defaults", defaults)) {
        problems.unknownKeys("defaults", defaults, DEFAULT_KEYS);
        defaultRetry = retry(defaults.get("retry"), RetryPolicy.NONE, "defaults.retry", problems);
        defaultTimeout = duration(defaults, "timeout", "defaults", problems, DEFAULT_TIMEOUT);
      }
    }

    List<StageDefinition> stages = new ArrayList<>();
    JsonNode stageNodes = root.get("stages");
    if (stageNodes == null || !stageNodes.isArray() || stageNodes.isEmpty()) {
      problems.add("stages", "at least one stage is required");
    } else {
      int i = 0;
      for (JsonNode node : stageNodes) {
        StageDefinition stage =
            stage(node, "stages[" + i + "]", defaultRetry, defaultTimeout, problems);
        if (stage != null) {
          stages.add(stage);
        }
        i++;
      }
      validateGraph(stages, problems);
    }

    if (problems.any()) {
      throw problems.exception(source);
    }
    return new WorkflowDefinition(id, version, budgets, stages);
  }

  // ---- sections ---------------------------------------------------------------------------

  private Budgets budgets(JsonNode node, Problems p) {
    if (node == null) {
      p.add("budgets", "is required");
      return null;
    }
    if (!p.requireObject("budgets", node)) {
      return null;
    }
    p.unknownKeys("budgets", node, BUDGET_KEYS);
    Duration wall = duration(node, "maxWallClock", "budgets", p, null);
    int calls = integer(node, "maxLlmCalls", "budgets", p, null, 0, Integer.MAX_VALUE);
    long tokens = integer(node, "maxTokens", "budgets", p, null, 0, Integer.MAX_VALUE);
    if (wall == null) {
      p.add("budgets.maxWallClock", "is required");
      return null;
    }
    return p.attempt("budgets", () -> new Budgets(wall, calls, tokens));
  }

  private RetryPolicy retry(JsonNode node, RetryPolicy base, String path, Problems p) {
    if (node == null) {
      return base;
    }
    if (!p.requireObject(path, node)) {
      return base;
    }
    p.unknownKeys(path, node, RETRY_KEYS);
    int maxAttempts = integer(node, "maxAttempts", path, p, base.maxAttempts(), 1, 10);
    RetryPolicy.Backoff backoff = base.backoff();
    String backoffText = string(node, "backoff", path, p, false);
    if (backoffText != null) {
      try {
        backoff = RetryPolicy.Backoff.valueOf(backoffText.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        p.add(path + ".backoff", "must be 'fixed' or 'exponential', was '" + backoffText + "'");
      }
    }
    Duration baseDelay = duration(node, "baseDelay", path, p, base.baseDelay());
    Duration maxDelay = duration(node, "maxDelay", path, p, base.maxDelay());
    boolean jitter = bool(node, "jitter", path, p, base.jitter());
    RetryPolicy.Backoff b = backoff;
    RetryPolicy result =
        p.attempt(path, () -> new RetryPolicy(maxAttempts, b, baseDelay, maxDelay, jitter));
    return result == null ? base : result;
  }

  private StageDefinition stage(
      JsonNode node, String at, RetryPolicy defaultRetry, Duration defaultTimeout, Problems p) {
    if (!p.requireObject(at, node)) {
      return null;
    }
    String id = string(node, "id", at, p, true);
    String path = id == null ? at : at + " (" + id + ")";
    p.unknownKeys(path, node, STAGE_KEYS);
    if (id != null && !STAGE_ID.matcher(id).matches()) {
      p.add(path + ".id", "must match [a-z][a-z0-9_]*");
    }

    String agent = string(node, "agent", path, p, false);
    String action = string(node, "action", path, p, false);
    Executor executor = null;
    if ((agent == null) == (action == null)) {
      p.add(path, "needs exactly one of 'agent' or 'action'");
    } else {
      executor = agent != null ? new Executor.Agent(agent) : new Executor.Action(action);
    }

    List<String> dependsOn = strings(node, "dependsOn", path, p);
    Optional<String> condition = Optional.ofNullable(string(node, "condition", path, p, false));
    condition.ifPresent(c -> p.attempt(path + ".condition", () -> Condition.parse(c)));
    List<GateRef> entryGates = gates(node, "entryGates", path, p);
    List<GateRef> exitGates = gates(node, "exitGates", path, p);
    Approval approval = approval(node.get("approval"), path + ".approval", p);
    RetryPolicy retry = retry(node.get("retry"), defaultRetry, path + ".retry", p);
    Optional<FallbackPolicy> fallback = fallback(node.get("fallback"), path + ".fallback", p);
    CheckpointKind checkpoint = checkpoint(node, path, p);
    Optional<OnFailure> onFailure = onFailure(node.get("onFailure"), path + ".onFailure", p);
    Optional<String> expand = Optional.ofNullable(string(node, "expand", path, p, false));
    if (expand.isPresent() && !SUPPORTED_EXPANSIONS.contains(expand.get())) {
      p.add(path + ".expand", "unsupported: '" + expand.get() + "' (supported: tasks)");
    }
    boolean highImpact = bool(node, "highImpact", path, p, false);
    Duration timeout = duration(node, "timeout", path, p, defaultTimeout);

    if (id == null || executor == null) {
      return null;
    }
    Executor e = executor;
    return p.attempt(
        path,
        () ->
            new StageDefinition(
                id,
                e,
                dependsOn,
                condition,
                entryGates,
                exitGates,
                approval,
                retry,
                fallback,
                checkpoint,
                onFailure,
                expand,
                highImpact,
                timeout));
  }

  private Approval approval(JsonNode node, String path, Problems p) {
    if (node == null) {
      return Approval.NONE;
    }
    if (!p.requireObject(path, node)) {
      return Approval.NONE;
    }
    p.unknownKeys(path, node, APPROVAL_KEYS);
    boolean required = bool(node, "required", path, p, false);
    String reason = string(node, "reason", path, p, false);
    Optional<Duration> timeout =
        node.has("timeout")
            ? Optional.ofNullable(duration(node, "timeout", path, p, null))
            : Optional.empty();
    Approval a = p.attempt(path, () -> new Approval(required, reason, timeout));
    return a == null ? Approval.NONE : a;
  }

  private Optional<FallbackPolicy> fallback(JsonNode node, String path, Problems p) {
    if (node == null || !p.requireObject(path, node)) {
      return Optional.empty();
    }
    p.unknownKeys(path, node, FALLBACK_KEYS);
    String strategy = string(node, "strategy", path, p, true);
    return strategy == null
        ? Optional.empty()
        : Optional.ofNullable(p.attempt(path, () -> new FallbackPolicy(strategy)));
  }

  private Optional<OnFailure> onFailure(JsonNode node, String path, Problems p) {
    if (node == null || !p.requireObject(path, node)) {
      return Optional.empty();
    }
    p.unknownKeys(path, node, ON_FAILURE_KEYS);
    String loopTo = string(node, "loopTo", path, p, true);
    int maxLoops = integer(node, "maxLoops", path, p, null, 1, 10);
    Optional<String> feedback = Optional.ofNullable(string(node, "feedback", path, p, false));
    if (loopTo == null || maxLoops < 1) {
      return Optional.empty();
    }
    return Optional.ofNullable(p.attempt(path, () -> new OnFailure(loopTo, maxLoops, feedback)));
  }

  private CheckpointKind checkpoint(JsonNode node, String path, Problems p) {
    String text = string(node, "checkpoint", path, p, false);
    if (text == null) {
      return CheckpointKind.NONE;
    }
    try {
      return CheckpointKind.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      p.add(path + ".checkpoint", "must be 'none' or 'git', was '" + text + "'");
      return CheckpointKind.NONE;
    }
  }

  private List<GateRef> gates(JsonNode node, String key, String path, Problems p) {
    List<GateRef> gates = new ArrayList<>();
    List<String> specs = strings(node, key, path, p);
    for (int i = 0; i < specs.size(); i++) {
      String spec = specs.get(i);
      GateRef gate =
          p.attempt(
              path + "." + key + "[" + i + "]",
              () -> {
                GateRef parsed = GateRef.parse(spec);
                gateCatalog.check(parsed);
                return parsed;
              });
      if (gate != null) {
        gates.add(gate);
      }
    }
    return gates;
  }

  // ---- whole-graph checks -----------------------------------------------------------------

  private void validateGraph(List<StageDefinition> stages, Problems p) {
    Map<String, StageDefinition> byId = new HashMap<>();
    for (StageDefinition s : stages) {
      if (byId.putIfAbsent(s.id(), s) != null) {
        p.add("stages", "duplicate stage id '" + s.id() + "'");
      }
    }
    boolean edgesValid = true;
    for (StageDefinition s : stages) {
      Set<String> seen = new HashSet<>();
      for (String dep : s.dependsOn()) {
        String at = "stage " + s.id() + ".dependsOn";
        if (dep.equals(s.id())) {
          p.add(at, "a stage can't depend on itself");
          edgesValid = false;
        } else if (!byId.containsKey(dep)) {
          p.add(at, "unknown stage '" + dep + "'");
          edgesValid = false;
        }
        if (!seen.add(dep)) {
          p.add(at, "'" + dep + "' is listed twice");
        }
      }
    }
    if (!edgesValid) {
      return; // cycle and ancestry checks need a well-formed graph
    }
    findCycle(stages, byId).ifPresent(cycle -> p.add("stages", "dependency cycle: " + cycle));

    for (StageDefinition s : stages) {
      s.onFailure()
          .ifPresent(f -> {
            String at = "stage " + s.id() + ".onFailure.loopTo";
            if (!byId.containsKey(f.loopTo())) {
              p.add(at, "unknown stage '" + f.loopTo() + "'");
            } else if (!upstream(s.id(), byId).contains(f.loopTo())) {
              p.add(at, "'" + f.loopTo() + "' must be upstream of '" + s.id() + "'");
            }
          });
    }
  }

  /** A cycle as "a -> b -> a", found by DFS with an on-stack set. */
  private static Optional<String> findCycle(
      List<StageDefinition> stages, Map<String, StageDefinition> byId) {
    Set<String> done = new HashSet<>();
    for (StageDefinition start : stages) {
      Optional<String> cycle = dfs(start.id(), byId, done, new LinkedHashSet<>());
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> dfs(
      String id,
      Map<String, StageDefinition> byId,
      Set<String> done,
      LinkedHashSet<String> onStack) {
    if (done.contains(id)) {
      return Optional.empty();
    }
    if (onStack.contains(id)) {
      List<String> cycle = new ArrayList<>();
      boolean in = false;
      for (String s : onStack) {
        in |= s.equals(id);
        if (in) {
          cycle.add(s);
        }
      }
      cycle.add(id);
      return Optional.of(String.join(" -> ", cycle));
    }
    onStack.add(id);
    for (String dep : byId.get(id).dependsOn()) {
      Optional<String> cycle = dfs(dep, byId, done, onStack);
      if (cycle.isPresent()) {
        return cycle;
      }
    }
    onStack.remove(id);
    done.add(id);
    return Optional.empty();
  }

  /** Every stage {@code id} transitively depends on. */
  private static Set<String> upstream(String id, Map<String, StageDefinition> byId) {
    Set<String> seen = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>(byId.get(id).dependsOn());
    while (!todo.isEmpty()) {
      String next = todo.pop();
      if (seen.add(next)) {
        todo.addAll(byId.get(next).dependsOn());
      }
    }
    return seen;
  }

  // ---- scalar helpers ---------------------------------------------------------------------

  private static String string(
      JsonNode node, String key, String path, Problems p, boolean required) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      if (required) {
        p.add(join(path, key), "is required");
      }
      return null;
    }
    if (!v.isString()) {
      p.add(join(path, key), "must be a string");
      return null;
    }
    return v.stringValue();
  }

  private static int integer(
      JsonNode node, String key, String path, Problems p, Integer dflt, int min, int max) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      if (dflt == null) {
        p.add(join(path, key), "is required");
        return min - 1;
      }
      return dflt;
    }
    if (!v.isIntegralNumber() || !v.canConvertToInt()) {
      p.add(join(path, key), "must be a whole number");
      return min - 1;
    }
    int value = v.intValue();
    if (value < min || value > max) {
      p.add(join(path, key), "must be between " + min + " and " + max + ", was " + value);
    }
    return value;
  }

  private static boolean bool(JsonNode node, String key, String path, Problems p, boolean dflt) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return dflt;
    }
    if (!v.isBoolean()) {
      p.add(join(path, key), "must be true or false");
      return dflt;
    }
    return v.booleanValue();
  }

  private static Duration duration(
      JsonNode node, String key, String path, Problems p, Duration dflt) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return dflt;
    }
    String text = v.isString() ? v.stringValue() : v.toString();
    Matcher m = DURATION.matcher(text.strip());
    if (!m.matches()) {
      p.add(join(path, key), "must be a duration like 500ms, 2s, 5m, 1h or 1d, was '" + text + "'");
      return dflt;
    }
    long amount = Long.parseLong(m.group(1));
    Duration d =
        switch (m.group(2)) {
          case "ms" -> Duration.ofMillis(amount);
          case "s" -> Duration.ofSeconds(amount);
          case "m" -> Duration.ofMinutes(amount);
          case "h" -> Duration.ofHours(amount);
          default -> Duration.ofDays(amount);
        };
    if (d.isZero()) {
      p.add(join(path, key), "must be greater than zero");
      return dflt;
    }
    return d;
  }

  private static List<String> strings(JsonNode node, String key, String path, Problems p) {
    JsonNode v = node.get(key);
    if (v == null || v.isNull()) {
      return List.of();
    }
    if (!v.isArray()) {
      p.add(join(path, key), "must be a list");
      return List.of();
    }
    List<String> values = new ArrayList<>();
    int i = 0;
    for (JsonNode item : v) {
      if (item.isString() && !item.stringValue().isBlank()) {
        values.add(item.stringValue());
      } else {
        p.add(join(path, key) + "[" + i + "]", "must be a non-empty string");
      }
      i++;
    }
    return values;
  }

  private static String join(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  /** Collected problems, each "location: message". */
  private static final class Problems {
    private final List<String> list = new ArrayList<>();

    void add(String path, String message) {
      list.add(path.isEmpty() ? message : path + ": " + message);
    }

    boolean any() {
      return !list.isEmpty();
    }

    boolean requireObject(String path, JsonNode node) {
      if (node.isObject()) {
        return true;
      }
      add(path, "must be a mapping");
      return false;
    }

    void unknownKeys(String path, JsonNode node, Set<String> allowed) {
      for (String key : node.propertyNames()) {
        if (!allowed.contains(key)) {
          add(join(path, key), "unknown key (allowed: " + String.join(", ", sorted(allowed)) + ")");
        }
      }
    }

    /** Runs a constructor; its IllegalArgumentException becomes a problem at {@code path}. */
    <T> T attempt(String path, Supplier<T> build) {
      try {
        return build.get();
      } catch (IllegalArgumentException | NullPointerException e) {
        add(path, e.getMessage());
        return null;
      }
    }

    WorkflowValidationException exception(String source) {
      return new WorkflowValidationException(source, list);
    }

    private static List<String> sorted(Set<String> keys) {
      return keys.stream().sorted().toList();
    }
  }
}
