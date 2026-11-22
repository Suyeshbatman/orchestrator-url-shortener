package com.acme.orchestrator.cli;

import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.RunMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A scripted demo run: {@code scenarios/<id>/} holds {@code requirement.md}, {@code scenario.yml}
 * (how the run is started and what the scripted human does at each checkpoint), optionally {@code
 * answers.json}, and the recorded {@code replay/} fixtures.
 *
 * <pre>
 * mode: BROWNFIELD                 # GREENFIELD | BROWNFIELD
 * autonomy: L1_SUPERVISED
 * base: main                       # the git ref the run starts from
 * expect: COMPLETED                # how the run should end
 * decisions:                       # approvals not listed are approved
 *   - { stage: design, decision: REJECT, comment: "use 410 not 404 for expired" }
 * amend:                           # optional: a requirement change once a stage is approved
 *   afterApprovalOf: design
 *   text: "Also block URL shorteners chaining to other shorteners."
 * </pre>
 *
 * Decisions for the same stage apply in order: the first listed decision answers that stage's first
 * approval request, the second its second, and so on.
 */
public record Scenario(
    String id,
    Path dir,
    String requirement,
    RunMode mode,
    AutonomyLevel autonomy,
    String base,
    String expect,
    List<ScriptedDecision> decisions,
    Optional<String> answersJson,
    Optional<Amendment> amend) {

  public record ScriptedDecision(String stage, Decision decision, String comment) {}

  public record Amendment(String afterApprovalOf, String text) {}

  private static final YAMLMapper YAML = YAMLMapper.builder().build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> KEYS =
      Set.of("mode", "autonomy", "base", "expect", "decisions", "amend");

  public Scenario {
    Objects.requireNonNull(id, "id");
    decisions = List.copyOf(decisions);
  }

  public Path replayDir() {
    return dir.resolve("replay");
  }

  /** Hand-written replies ({@code <Agent>-<n>.json}) that take precedence over the model. */
  public Path repliesDir() {
    return dir.resolve("replies");
  }

  public static Scenario load(Path scenariosDir, String id) {
    if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
      throw new IllegalArgumentException("not a scenario id: " + id);
    }
    Path dir = scenariosDir.resolve(id);
    Path requirement = dir.resolve("requirement.md");
    if (!Files.isRegularFile(requirement)) {
      throw new IllegalArgumentException("no scenario " + id + " (missing " + requirement + ")");
    }
    JsonNode yml = YAML.readTree(read(dir.resolve("scenario.yml")));
    List<String> unknown = new ArrayList<>();
    yml.propertyNames()
        .forEach(k -> {
          if (!KEYS.contains(k)) {
            unknown.add(k);
          }
        });
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(id + "/scenario.yml has unknown keys: " + unknown);
    }
    List<ScriptedDecision> decisions = new ArrayList<>();
    for (JsonNode d : yml.path("decisions")) {
      decisions.add(
          new ScriptedDecision(
              required(d, "stage", id),
              Decision.valueOf(required(d, "decision", id)),
              d.path("comment").asString("")));
    }
    Optional<Amendment> amend =
        yml.has("amend")
            ? Optional.of(
                new Amendment(
                    required(yml.path("amend"), "afterApprovalOf", id),
                    required(yml.path("amend"), "text", id)))
            : Optional.empty();
    Path answers = dir.resolve("answers.json");
    return new Scenario(
        id,
        dir,
        read(requirement).strip(),
        RunMode.valueOf(yml.path("mode").asString("GREENFIELD")),
        AutonomyLevel.valueOf(yml.path("autonomy").asString("L1_SUPERVISED")),
        yml.path("base").asString("main"),
        yml.path("expect").asString("COMPLETED"),
        decisions,
        Files.isRegularFile(answers) ? Optional.of(canonical(read(answers))) : Optional.empty(),
        amend);
  }

  /** The answers as the API stores them (canonical JSON), so CLI and API runs render one prompt. */
  private static String canonical(String json) {
    return CanonicalJson.write(JSON.readValue(json, Object.class));
  }

  private static String required(JsonNode node, String key, String id) {
    String value = node.path(key).asString("");
    if (value.isBlank()) {
      throw new IllegalArgumentException(id + "/scenario.yml: '" + key + "' is required");
    }
    return value;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }
}
