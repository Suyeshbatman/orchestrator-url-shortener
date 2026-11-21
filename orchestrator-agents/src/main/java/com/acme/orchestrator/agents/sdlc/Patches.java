package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.FileTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Applying {@code patch}-shaped artifacts ({@code files: [{path, newContent}], deletions}). */
final class Patches {

  static final int MAX_CONTEXT_CHARS = 60_000;

  private Patches() {}

  /**
   * Writes every file and removes every deletion through {@code files}, so each path is
   * policy-checked; a DENY stops the attempt (PolicyDeniedException).
   *
   * @return the paths touched
   */
  static List<String> apply(FileTool files, JsonNode patch) {
    List<String> touched = new ArrayList<>();
    for (JsonNode file : patch.path("files")) {
      String path = file.path("path").asString("");
      files.write(path, file.path("newContent").asString(""));
      touched.add(path);
    }
    for (JsonNode deletion : patch.path("deletions")) {
      files.delete(deletion.asString(""));
      touched.add(deletion.asString(""));
    }
    return touched;
  }

  /** The current content of those {@code paths} that exist, as Markdown, within a size budget. */
  static String currentContent(FileTool files, Collection<String> paths) {
    StringBuilder out = new StringBuilder();
    Set<String> seen = new LinkedHashSet<>(paths);
    for (String path : seen) {
      if (path.isBlank() || !files.exists(path)) {
        continue;
      }
      String content = files.read(path);
      if (out.length() + content.length() > MAX_CONTEXT_CHARS) {
        out.append("### ").append(path).append("\n(omitted: the context budget is used up)\n\n");
        continue;
      }
      out.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n");
    }
    return out.isEmpty() ? "None of the files exist yet." : out.toString().strip();
  }
}
