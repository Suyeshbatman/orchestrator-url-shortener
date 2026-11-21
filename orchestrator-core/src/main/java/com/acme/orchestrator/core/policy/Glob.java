package com.acme.orchestrator.core.policy;

import java.util.regex.Pattern;

/**
 * A path glob over {@code /}-separated relative paths: {@code **} matches any number of segments
 * (including none, so {@code **}{@code /pom.xml} matches {@code pom.xml}), {@code *} and {@code ?}
 * stay within one segment. Everything else is literal.
 */
record Glob(String glob, Pattern regex) {

  static Glob of(String glob) {
    if (glob == null || glob.isBlank()) {
      throw new IllegalArgumentException("a path glob must not be blank");
    }
    StringBuilder re = new StringBuilder();
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (glob.startsWith("**/", i)) {
        re.append("(?:[^/]+/)*");
        i += 3;
      } else if (glob.startsWith("**", i)) {
        re.append(".*");
        i += 2;
      } else if (c == '*') {
        re.append("[^/]*");
        i++;
      } else if (c == '?') {
        re.append("[^/]");
        i++;
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
        i++;
      }
    }
    return new Glob(glob, Pattern.compile(re.toString()));
  }

  boolean matches(String path) {
    return regex.matcher(path).matches();
  }

  @Override
  public String toString() {
    return glob;
  }
}
