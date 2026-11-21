package com.acme.orchestrator.agents.llm;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrubs secrets from text before it leaves the process (prompts, and so also fixtures). Two
 * layers: the actual values of secret-looking environment variables (exact match, whatever their
 * shape), and patterns for common secret formats and {@code name = value} assignments.
 */
public final class SecretRedactor {

  public static final String REDACTED = "[REDACTED]";

  /** Variable names that hold secrets. */
  private static final Pattern SECRET_NAME =
      Pattern.compile(
          "(?i).*(secret|password|passwd|token|api[_-]?key|private[_-]?key|credential).*");

  /** Values shorter than this are too likely to occur by accident to replace verbatim. */
  private static final int MIN_SECRET_LENGTH = 8;

  private static final List<Pattern> PATTERNS =
      List.of(
          Pattern.compile(
              "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
          Pattern.compile("AKIA[0-9A-Z]{16}"),
          Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}"),
          Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
          Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{16,}"),
          // NAME=value, "name": "value", name: value
          Pattern.compile(
              "(?i)([\"']?[A-Za-z0-9_.-]*(?:secret|password|passwd|token|api[_-]?key)[A-Za-z0-9_.-]*"
                  + "[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;]{6,})"));

  private final List<String> knownSecrets;

  /**
   * @param knownSecrets exact values to remove wherever they appear (short ones are ignored)
   */
  public SecretRedactor(Collection<String> knownSecrets) {
    this.knownSecrets =
        knownSecrets.stream()
            .filter(Objects::nonNull)
            .filter(s -> s.length() >= MIN_SECRET_LENGTH)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed()) // longest first
            .toList();
  }

  /** Treats the values of secret-named variables (API keys, passwords, tokens) as known secrets. */
  public static SecretRedactor fromEnvironment(Map<String, String> env) {
    return new SecretRedactor(
        env.entrySet().stream()
            .filter(e -> SECRET_NAME.matcher(e.getKey()).matches())
            .map(Map.Entry::getValue)
            .toList());
  }

  public String redact(String text) {
    String out = text;
    for (String secret : knownSecrets) {
      out = out.replace(secret, REDACTED);
    }
    for (Pattern p : PATTERNS) {
      Matcher m = p.matcher(out);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        // keep the name / "Bearer " prefix when the pattern captured one
        String replacement = m.groupCount() >= 1 ? m.group(1) + REDACTED : REDACTED;
        m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(sb);
      out = sb.toString();
    }
    return out;
  }
}
