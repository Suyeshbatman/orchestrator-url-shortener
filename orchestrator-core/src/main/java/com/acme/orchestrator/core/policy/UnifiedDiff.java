package com.acme.orchestrator.core.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a unified diff the policies look at: every path it touches (old and new, so deleting
 * a protected file counts too) and the added lines with their line numbers in the new file.
 */
record UnifiedDiff(Set<String> paths, List<AddedLine> added) {

  record AddedLine(String path, int line, String text) {}

  private static final Pattern GIT_HEADER = Pattern.compile("diff --git a/(\\S+) b/(\\S+)");
  private static final Pattern HUNK =
      Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

  static UnifiedDiff parse(String diff) {
    Set<String> paths = new LinkedHashSet<>();
    List<AddedLine> added = new ArrayList<>();
    String[] lines = diff.split("\r?\n", -1);
    String current = "";
    int newLine = 0;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      Matcher git = GIT_HEADER.matcher(line);
      if (git.matches()) {
        paths.add(git.group(1));
        paths.add(git.group(2));
        continue;
      }
      // a file header is a "--- " line directly followed by a "+++ " line
      if (line.startsWith("--- ") && i + 1 < lines.length && lines[i + 1].startsWith("+++ ")) {
        String oldPath = headerPath(line, "a/");
        String newPath = headerPath(lines[i + 1], "b/");
        if (oldPath != null) {
          paths.add(oldPath);
        }
        if (newPath != null) {
          paths.add(newPath);
        }
        current = newPath != null ? newPath : oldPath == null ? "" : oldPath;
        i++;
        continue;
      }
      Matcher hunk = HUNK.matcher(line);
      if (hunk.matches()) {
        newLine = Integer.parseInt(hunk.group(1));
      } else if (line.startsWith("+")) {
        added.add(new AddedLine(current, newLine++, line.substring(1)));
      } else if (line.startsWith(" ")) {
        newLine++;
      }
    }
    return new UnifiedDiff(paths, added);
  }

  /** The path in a {@code ---}/{@code +++} header, or null for {@code /dev/null}. */
  private static String headerPath(String header, String prefix) {
    String path = header.substring(4);
    int tab = path.indexOf('\t');
    if (tab >= 0) {
      path = path.substring(0, tab); // drop a timestamp
    }
    path = path.strip();
    if (path.equals("/dev/null")) {
      return null;
    }
    return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
  }
}
