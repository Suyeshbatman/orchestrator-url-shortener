package com.acme.orchestrator.agents.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Keeps file access inside the workspace: a path must be relative, may not climb out with {@code
 * ..}, may not go through a symlink that leads outside (checked on the real path of the deepest
 * existing ancestor), and may not touch {@code .git}.
 */
final class PathGuard {

  private final Path root;
  private final Path realRoot;

  PathGuard(Path root) {
    this.root = root.toAbsolutePath().normalize();
    try {
      this.realRoot = this.root.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("workspace root does not exist: " + root, e);
    }
  }

  /**
   * @return the absolute path inside the workspace
   * @throws PathEscapeException if {@code relative} would leave it
   */
  Path resolve(String relative) {
    if (relative == null || relative.isBlank()) {
      throw new PathEscapeException("a path is required");
    }
    String unified = relative.replace('\\', '/');
    if (unified.startsWith("/") || unified.matches("[A-Za-z]:.*") || unified.startsWith("~")) {
      throw new PathEscapeException("absolute paths are not allowed: " + relative);
    }
    Path resolved = root.resolve(unified).normalize();
    if (!resolved.startsWith(root)) {
      throw new PathEscapeException("path leaves the workspace: " + relative);
    }
    Path inside = root.relativize(resolved);
    if (inside.getNameCount() > 0 && inside.getName(0).toString().equalsIgnoreCase(".git")) {
      throw new PathEscapeException("the repository's .git directory is off limits: " + relative);
    }
    // symlinks: the real location of the deepest existing ancestor must still be inside
    Path existing = resolved;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing != null) {
      try {
        if (!existing.toRealPath().startsWith(realRoot)) {
          throw new PathEscapeException("path leads outside the workspace via a link: " + relative);
        }
      } catch (IOException e) {
        throw new PathEscapeException("cannot resolve " + relative + ": " + e.getMessage());
      }
    }
    return resolved;
  }

  /** The workspace-relative form, with {@code /} separators (what policies match on). */
  String relative(Path absolute) {
    return root.relativize(absolute).toString().replace('\\', '/');
  }
}
