package com.acme.orchestrator.core.policy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One rule from {@code config/policies.yml}. The kind follows from its keys (see {@link
 * PolicyLoader}); {@code weight} is its contribution to the risk score when it matches.
 */
public sealed interface Policy {

  int DEFAULT_DENY_WEIGHT = 50;
  int DEFAULT_APPROVAL_WEIGHT = 20;

  String id();

  /** The effect when the rule matches (for allowlists: the effect outside the list). */
  Effect effect();

  int weight();

  Optional<String> message();

  static int defaultWeight(Effect effect) {
    return switch (effect) {
      case DENY -> DEFAULT_DENY_WEIGHT;
      case REQUIRE_APPROVAL -> DEFAULT_APPROVAL_WEIGHT;
      case ALLOW -> 0;
    };
  }

  /** {@code on: diff} + {@code deny_patterns}: an added line matches a pattern. */
  record DenyPatterns(
      String id, List<Pattern> patterns, Effect effect, int weight, Optional<String> message)
      implements Policy {
    public DenyPatterns {
      Objects.requireNonNull(id, "id");
      patterns = List.copyOf(patterns);
    }
  }

  /** {@code on: file_write} + {@code allow_paths}: writing outside the jail. */
  record PathJail(
      String id, List<Glob> allowPaths, Effect effect, int weight, Optional<String> message)
      implements Policy {
    public PathJail {
      Objects.requireNonNull(id, "id");
      allowPaths = List.copyOf(allowPaths);
    }

    boolean allows(String path) {
      return allowPaths.stream().anyMatch(g -> g.matches(path));
    }
  }

  /** {@code on: file_write} + {@code paths}: writing a matching path. */
  /**
   * @param unreviewedOnly {@code when: unreviewed}: applies only to writes of model output no human
   *     has reviewed yet
   */
  record PathRule(
      String id,
      List<Glob> paths,
      Effect effect,
      int weight,
      Optional<String> message,
      boolean unreviewedOnly)
      implements Policy {
    public PathRule {
      Objects.requireNonNull(id, "id");
      paths = List.copyOf(paths);
    }

    public PathRule(
        String id, List<Glob> paths, Effect effect, int weight, Optional<String> message) {
      this(id, paths, effect, weight, message, false);
    }

    Optional<Glob> match(String path) {
      return paths.stream().filter(g -> g.matches(path)).findFirst();
    }
  }

  /** {@code on: diff} + {@code max_files_changed} / {@code max_lines_changed}. */
  record SizeLimit(
      String id,
      Optional<Integer> maxFiles,
      Optional<Integer> maxLines,
      Effect effect,
      int weight,
      Optional<String> message)
      implements Policy {
    public SizeLimit {
      Objects.requireNonNull(id, "id");
    }
  }

  /** {@code on: command} + {@code allow}: a command line not exactly on the list. */
  record CommandAllowlist(
      String id, List<String> allow, Effect effect, int weight, Optional<String> message)
      implements Policy {
    public CommandAllowlist {
      Objects.requireNonNull(id, "id");
      allow = List.copyOf(allow);
    }
  }
}
