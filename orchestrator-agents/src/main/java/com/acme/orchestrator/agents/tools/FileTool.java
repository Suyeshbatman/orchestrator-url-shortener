package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.Effect;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads, lists and searches the workspace, and writes to it after asking the policy engine. A DENY
 * refuses the write; REQUIRE_APPROVAL lets it happen on the run's own branch, and the stage then
 * waits for a human before it can succeed (nothing reaches main without publish's approval).
 */
public final class FileTool {

  /** A search hit. */
  public record Match(String path, int line, String text) {}

  static final int MAX_MATCHES = 200;
  static final int MAX_FILE_BYTES = 1_000_000;

  private final PathGuard guard;
  private final Authorizer authorizer;

  public FileTool(Workspace workspace, Authorizer authorizer) {
    this.guard = new PathGuard(workspace.root());
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
  }

  public String read(String path) {
    Path file = guard.resolve(path);
    try {
      if (Files.size(file) > MAX_FILE_BYTES) {
        throw new IllegalArgumentException(path + " is larger than " + MAX_FILE_BYTES + " bytes");
      }
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  public boolean exists(String path) {
    return Files.exists(guard.resolve(path));
  }

  /** Files under {@code dir} (recursively), workspace-relative, sorted; skips .git and target. */
  public List<String> list(String dir) {
    Path start = dir.isBlank() || dir.equals(".") ? guard.resolve(".") : guard.resolve(dir);
    try (Stream<Path> paths = Files.walk(start)) {
      return paths
          .filter(Files::isRegularFile)
          .map(guard::relative)
          .filter(
              p -> !p.startsWith(".git/") && !p.contains("/target/") && !p.startsWith("target/"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + dir, e);
    }
  }

  /** Lines matching {@code regex} in files under {@code dir}; at most {@value #MAX_MATCHES}. */
  public List<Match> search(String dir, String regex) {
    Pattern pattern = Pattern.compile(regex);
    List<Match> matches = new ArrayList<>();
    for (String path : list(dir)) {
      List<String> lines;
      try {
        lines = Files.readAllLines(guard.resolve(path), StandardCharsets.UTF_8);
      } catch (MalformedInputException binary) {
        continue;
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read " + path, e);
      }
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = pattern.matcher(lines.get(i));
        if (m.find()) {
          matches.add(new Match(path, i + 1, lines.get(i).strip()));
          if (matches.size() >= MAX_MATCHES) {
            return matches;
          }
        }
      }
    }
    return matches;
  }

  /**
   * Writes {@code content} to {@code path} once the policy engine has seen it.
   *
   * @return the decision (REQUIRE_APPROVAL means the stage will wait for a human)
   * @throws PolicyDeniedException if the policy says DENY (nothing is written)
   * @throws PathEscapeException if the path leaves the workspace
   */
  public Decision write(String path, String content) {
    Path file = guard.resolve(path);
    String relative = guard.relative(file);
    Decision decision = authorizer.authorize(new ProposedAction.FileWrite(relative, content));
    if (decision.effect() == Effect.DENY) {
      throw new PolicyDeniedException("writing " + relative, decision);
    }
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + relative, e);
    }
    return decision;
  }

  /** Deletes a file; checked like a write (it changes the path). */
  public Decision delete(String path) {
    Path file = guard.resolve(path);
    String relative = guard.relative(file);
    Decision decision = authorizer.authorize(new ProposedAction.FileWrite(relative, ""));
    if (decision.effect() == Effect.DENY) {
      throw new PolicyDeniedException("deleting " + relative, decision);
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete " + relative, e);
    }
    return decision;
  }
}
