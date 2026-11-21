package com.acme.orchestrator.core.policy;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads {@code policies.yml} and {@code autonomy.yml} (BUILD_PLAN §6.4/§6.5). Strict like the
 * workflow loader: unknown keys, unknown rule kinds and bad values are errors, all reported
 * together.
 *
 * <p>A rule's kind follows from {@code on} and its keys:
 *
 * <ul>
 *   <li>{@code diff} + {@code deny_patterns} + {@code effect}: forbidden content in added lines
 *   <li>{@code diff} + {@code max_files_changed}/{@code max_lines_changed} + {@code effect}
 *   <li>{@code file_write} + {@code allow_paths} + {@code effect_outside}: path jail
 *   <li>{@code file_write} + {@code paths} + {@code effect}: protected paths
 *   <li>{@code command} + {@code allow} + {@code effect_outside}: exact-match allowlist
 * </ul>
 *
 * Every rule may also have {@code message} and {@code weight} (0–100, its risk contribution).
 */
public final class PolicyLoader {

  public static final String POLICIES_FILE = "policies.yml";
  public static final String AUTONOMY_FILE = "autonomy.yml";

  private static final Set<String> COMMON_KEYS = Set.of("id", "on", "message", "weight");

  private final YAMLMapper yaml = YAMLMapper.builder().build();

  /** Loads both files from {@code configDir} (normally {@code config/}). */
  public PolicySet load(Path configDir) {
    return parse(read(configDir.resolve(POLICIES_FILE)), read(configDir.resolve(AUTONOMY_FILE)));
  }

  /**
   * @throws PolicyValidationException listing every problem in both files
   */
  public PolicySet parse(String policiesYaml, String autonomyYaml) {
    List<String> problems = new ArrayList<>();
    List<Policy> policies = policies(tree(policiesYaml, POLICIES_FILE, problems), problems);
    Map<AutonomyLevel, String> levels =
        autonomy(tree(autonomyYaml, AUTONOMY_FILE, problems), problems);
    if (!problems.isEmpty()) {
      throw new PolicyValidationException(problems);
    }
    return new PolicySet(policies, levels, hash(policiesYaml), hash(autonomyYaml));
  }

  /** SHA-256 of the text with line endings normalized, so the hash is the same on every OS. */
  static String hash(String text) {
    return Hashing.sha256Hex(text.replace("\r\n", "\n"));
  }

  // ---- policies.yml -------------------------------------------------------------------------

  private List<Policy> policies(JsonNode root, List<String> problems) {
    List<Policy> policies = new ArrayList<>();
    if (root == null) {
      return policies;
    }
    unknownKeys(root, Set.of("policies"), POLICIES_FILE, problems);
    JsonNode list = root.get("policies");
    if (list == null || !list.isArray()) {
      problems.add(POLICIES_FILE + ": 'policies' must be a list");
      return policies;
    }
    Set<String> ids = new HashSet<>();
    int i = 0;
    for (JsonNode node : list) {
      Rule r = new Rule(node, POLICIES_FILE + ": policies[" + i++ + "]", problems);
      Policy policy = r.build();
      JsonNode id = node.path("id");
      // ids of invalid rules count too, so a duplicate is reported either way
      if (id.isString() && !ids.add(id.stringValue())) {
        problems.add(r.path + ": duplicate policy id " + id.stringValue());
      } else if (policy != null) {
        policies.add(policy);
      }
    }
    return policies;
  }

  /** Reads one rule, adding problems instead of throwing. */
  private static final class Rule {
    private final JsonNode node;
    private String path;
    private final List<String> problems;
    private final int before;

    Rule(JsonNode node, String path, List<String> problems) {
      this.node = node;
      this.path = path;
      this.problems = problems;
      this.before = problems.size();
    }

    Policy build() {
      if (!node.isObject()) {
        problem("must be a mapping");
        return null;
      }
      String id = text("id");
      if (id == null) {
        problem("'id' is required");
        return null;
      }
      path = path + " (" + id + ")";
      String on = text("on");
      Optional<String> message = Optional.ofNullable(text("message"));
      Policy policy =
          switch (on == null ? "" : on) {
            case "diff" ->
                node.has("deny_patterns")
                    ? denyPatterns(id, message)
                    : node.has("max_files_changed") || node.has("max_lines_changed")
                        ? sizeLimit(id, message)
                        : unknownKind("a diff rule needs deny_patterns or max_*_changed");
            case "file_write" ->
                node.has("allow_paths")
                    ? jail(id, message)
                    : node.has("paths")
                        ? pathRule(id, message)
                        : unknownKind("a file_write rule needs allow_paths or paths");
            case "command" ->
                node.has("allow")
                    ? allowlist(id, message)
                    : unknownKind("a command rule needs allow");
            default -> unknownKind("'on' must be diff, file_write or command, was '" + on + "'");
          };
      return problems.size() == before ? policy : null;
    }

    private Policy denyPatterns(String id, Optional<String> message) {
      keys("deny_patterns", "effect");
      List<Pattern> patterns = new ArrayList<>();
      for (String regex : strings("deny_patterns")) {
        try {
          patterns.add(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
          problem("deny_patterns: invalid regex '" + regex + "': " + e.getDescription());
        }
      }
      Effect effect = effect("effect");
      return new Policy.DenyPatterns(id, patterns, effect, weight(effect), message);
    }

    private Policy sizeLimit(String id, Optional<String> message) {
      keys("max_files_changed", "max_lines_changed", "effect");
      Effect effect = effect("effect");
      return new Policy.SizeLimit(
          id,
          positive("max_files_changed"),
          positive("max_lines_changed"),
          effect,
          weight(effect),
          message);
    }

    private Policy jail(String id, Optional<String> message) {
      keys("allow_paths", "effect_outside");
      Effect effect = effect("effect_outside");
      return new Policy.PathJail(id, globs("allow_paths"), effect, weight(effect), message);
    }

    private Policy pathRule(String id, Optional<String> message) {
      keys("paths", "effect");
      Effect effect = effect("effect");
      return new Policy.PathRule(id, globs("paths"), effect, weight(effect), message);
    }

    private Policy allowlist(String id, Optional<String> message) {
      keys("allow", "effect_outside");
      Effect effect = effect("effect_outside");
      return new Policy.CommandAllowlist(id, strings("allow"), effect, weight(effect), message);
    }

    private Policy unknownKind(String why) {
      problem(why);
      return null;
    }

    private void keys(String... kindKeys) {
      Set<String> allowed = new HashSet<>(COMMON_KEYS);
      allowed.addAll(Arrays.asList(kindKeys));
      for (String key : node.propertyNames()) {
        if (!allowed.contains(key)) {
          problem("unknown key '" + key + "' for this kind of rule");
        }
      }
    }

    private String text(String key) {
      JsonNode v = node.get(key);
      if (v == null || v.isNull()) {
        return null;
      }
      if (!v.isString() || v.stringValue().isBlank()) {
        problem("'" + key + "' must be a non-empty string");
        return null;
      }
      return v.stringValue();
    }

    private List<String> strings(String key) {
      JsonNode v = node.get(key);
      List<String> values = new ArrayList<>();
      if (v == null || !v.isArray() || v.isEmpty()) {
        problem("'" + key + "' must be a non-empty list");
        return values;
      }
      for (JsonNode item : v) {
        if (item.isString() && !item.stringValue().isBlank()) {
          values.add(item.stringValue());
        } else {
          problem("'" + key + "' must hold non-empty strings");
        }
      }
      return values;
    }

    private List<Glob> globs(String key) {
      return strings(key).stream().map(Glob::of).toList();
    }

    /** DENY or REQUIRE_APPROVAL; a rule that ALLOWs would only ever be ignored. */
    private Effect effect(String key) {
      String text = text(key);
      if (text == null) {
        problem("'" + key + "' is required");
        return Effect.DENY;
      }
      if (text.equals("DENY") || text.equals("REQUIRE_APPROVAL")) {
        return Effect.valueOf(text);
      }
      problem("'" + key + "' must be DENY or REQUIRE_APPROVAL, was '" + text + "'");
      return Effect.DENY;
    }

    private Optional<Integer> positive(String key) {
      JsonNode v = node.get(key);
      if (v == null) {
        return Optional.empty();
      }
      if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 1) {
        problem("'" + key + "' must be a positive whole number");
        return Optional.empty();
      }
      return Optional.of(v.intValue());
    }

    private int weight(Effect effect) {
      JsonNode v = node.get("weight");
      if (v == null) {
        return Policy.defaultWeight(effect);
      }
      if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 0 || v.intValue() > 100) {
        problem("'weight' must be a whole number from 0 to 100");
        return 0;
      }
      return v.intValue();
    }

    private void problem(String message) {
      problems.add(path + ": " + message);
    }
  }

  // ---- autonomy.yml -------------------------------------------------------------------------

  private Map<AutonomyLevel, String> autonomy(JsonNode root, List<String> problems) {
    Map<AutonomyLevel, String> levels = new EnumMap<>(AutonomyLevel.class);
    if (root == null) {
      return levels;
    }
    unknownKeys(root, Set.of("levels", "invariants"), AUTONOMY_FILE, problems);

    JsonNode levelNodes = root.get("levels");
    if (levelNodes == null || !levelNodes.isObject()) {
      problems.add(AUTONOMY_FILE + ": 'levels' must be a mapping");
    } else {
      for (String name : levelNodes.propertyNames()) {
        String path = AUTONOMY_FILE + ": levels." + name;
        Optional<AutonomyLevel> level =
            Arrays.stream(AutonomyLevel.values()).filter(l -> l.name().equals(name)).findFirst();
        if (level.isEmpty()) {
          problems.add(path + ": unknown autonomy level");
          continue;
        }
        JsonNode body = levelNodes.get(name);
        JsonNode description = body.isObject() ? body.get("description") : null;
        if (description == null || !description.isString() || description.stringValue().isBlank()) {
          problems.add(path + ": needs a non-empty 'description'");
          continue;
        }
        unknownKeys(body, Set.of("description"), path, problems);
        levels.put(level.get(), description.stringValue());
      }
      for (AutonomyLevel level : AutonomyLevel.values()) {
        if (!levels.containsKey(level) && !levelNodes.has(level.name())) {
          problems.add(AUTONOMY_FILE + ": levels: " + level + " is missing");
        }
      }
    }

    JsonNode invariantNodes = root.get("invariants");
    Set<String> listed = new LinkedHashSet<>();
    if (invariantNodes == null || !invariantNodes.isArray()) {
      problems.add(AUTONOMY_FILE + ": 'invariants' must be a list");
    } else {
      invariantNodes.forEach(
          n -> listed.add(n.isString() ? n.stringValue().strip() : n.toString()));
    }
    Set<String> enforced =
        Arrays.stream(Invariant.values()).map(Invariant::text).collect(Collectors.toSet());
    listed.stream()
        .filter(text -> !enforced.contains(text))
        .forEach(text ->
            problems.add(
                AUTONOMY_FILE + ": invariant '" + text + "' is not one the engine enforces"));
    if (invariantNodes != null && invariantNodes.isArray()) {
      enforced.stream()
          .filter(text -> !listed.contains(text))
          .sorted()
          .forEach(text ->
              problems.add(
                  AUTONOMY_FILE
                      + ": invariant '"
                      + text
                      + "' is missing (it is enforced in code regardless)"));
    }
    return levels;
  }

  // ---- helpers ------------------------------------------------------------------------------

  private JsonNode tree(String text, String file, List<String> problems) {
    try {
      JsonNode root = yaml.readTree(text);
      if (root == null || !root.isObject()) {
        problems.add(file + ": the document must be a mapping");
        return null;
      }
      return root;
    } catch (JacksonException e) {
      problems.add(file + ": not valid YAML: " + e.getOriginalMessage());
      return null;
    }
  }

  private static void unknownKeys(
      JsonNode node, Set<String> allowed, String path, List<String> problems) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        problems.add(path + ": unknown key '" + key + "'");
      }
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }
}
