package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.core.model.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt templates, one per agent, on the classpath at {@code prompts/<Agent>.md}: a {@code ##
 * System} section and a {@code ## User} section, with {@code {{name}}} placeholders. A placeholder
 * without a value is an error (a silently empty prompt section is a bug that looks like a bad model
 * answer).
 */
public final class PromptTemplates {

  /** A rendered prompt; {@code hash} is recorded in the artifact's lineage. */
  public record Prompt(String system, String user, String hash) {
    public Prompt {
      Objects.requireNonNull(system, "system");
      Objects.requireNonNull(user, "user");
      Objects.requireNonNull(hash, "hash");
    }
  }

  private static final Pattern SECTIONS =
      Pattern.compile(
          "(?s)\\A.*?^## System[ \\t]*\\R(.*?)^## User[ \\t]*\\R(.*)\\z", Pattern.MULTILINE);
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

  private final String root;

  public PromptTemplates() {
    this("prompts");
  }

  public PromptTemplates(String classpathRoot) {
    this.root = classpathRoot;
  }

  /**
   * @throws IllegalArgumentException if the template is missing, malformed, or a placeholder has no
   *     value
   */
  public Prompt render(String agent, Map<String, String> values) {
    String template = read(agent);
    Matcher m = SECTIONS.matcher(template.replace("\r\n", "\n"));
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "prompt template for " + agent + " needs a '## System' and then a '## User' section");
    }
    String system = fill(agent, m.group(1).strip(), values);
    String user = fill(agent, m.group(2).strip(), values);
    return new Prompt(system, user, Hashing.sha256Hex(system + "\n\n---\n\n" + user));
  }

  private static String fill(String agent, String text, Map<String, String> values) {
    Set<String> missing = new LinkedHashSet<>();
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String value = values.get(m.group(1));
      if (value == null) {
        missing.add(m.group(1));
        value = "";
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    m.appendTail(sb);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "prompt for " + agent + " has no value for: " + String.join(", ", missing));
    }
    return sb.toString();
  }

  private String read(String agent) {
    if (!agent.matches("[A-Za-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException("not an agent name: " + agent);
    }
    String path = root + "/" + agent + ".md";
    try (InputStream in = PromptTemplates.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("no prompt template " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }
}
