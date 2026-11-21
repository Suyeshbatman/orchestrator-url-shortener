package com.acme.orchestrator.cli;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Where the CLI is and what it is configured with: the repository root (found by walking up to
 * {@code config/workflows}), and the environment overlaid on the repo-root {@code .env} (real
 * environment variables win, like the apps). Values are never printed.
 */
public final class Environment {

  private final Path repoRoot;
  private final Map<String, String> variables;

  private Environment(Path repoRoot, Map<String, String> variables) {
    this.repoRoot = repoRoot;
    this.variables = Map.copyOf(variables);
  }

  public static Environment discover(Path start, Map<String, String> processEnv) {
    Path root = findRoot(start);
    Map<String, String> merged = new HashMap<>(dotEnv(root.resolve(".env")));
    merged.putAll(processEnv);
    return new Environment(root, merged);
  }

  static Path findRoot(Path start) {
    for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("config/workflows/sdlc-standard.yml"))) {
        return p;
      }
    }
    throw new IllegalStateException(
        "run this from inside the repository (no config/workflows/sdlc-standard.yml above "
            + start
            + ")");
  }

  /** The {@code .env} file in .properties syntax (comments on their own lines), or nothing. */
  static Map<String, String> dotEnv(Path file) {
    if (!Files.isRegularFile(file)) {
      return Map.of();
    }
    Properties properties = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    Map<String, String> values = new HashMap<>();
    properties.forEach((k, v) -> values.put(k.toString(), v.toString().strip()));
    return values;
  }

  public Path repoRoot() {
    return repoRoot;
  }

  public Map<String, String> variables() {
    return variables;
  }

  public Environment with(String name, String value) {
    Map<String, String> copy = new HashMap<>(variables);
    copy.put(name, value);
    return new Environment(repoRoot, copy);
  }

  public boolean has(String name) {
    String value = variables.get(name);
    return value != null && !value.isBlank();
  }
}
