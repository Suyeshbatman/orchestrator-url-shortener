package com.acme.orchestrator.agents.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixture format: one {@code <request-hash>.json} per call in {@code scenarios/<id>/replay/},
 * holding the (redacted) request for reviewers and the reply. Pretty-printed and stable, so
 * fixtures diff well in code review.
 */
final class ReplayFiles {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private ReplayFiles() {}

  static Path file(Path dir, String hash) {
    return dir.resolve(hash + ".json");
  }

  static void write(Path dir, LlmRequest request, LlmResponse response) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("agent", request.agent());
    req.put("system", request.systemPrompt());
    req.put("user", request.userPrompt());
    request.jsonSchema().ifPresent(s -> req.put("schema", s));
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("text", response.text());
    res.put("model", response.model());
    res.put("inputTokens", response.inputTokens());
    res.put("outputTokens", response.outputTokens());
    res.put("latencyMs", response.latency().toMillis());
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("requestHash", request.hash());
    doc.put("request", req);
    doc.put("response", res);
    try {
      Files.createDirectories(dir);
      Path target = file(dir, request.hash());
      Path tmp = Files.createTempFile(dir, "replay", ".tmp");
      Files.writeString(tmp, JSON.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write replay fixture to " + dir, e);
    }
  }

  static Optional<LlmResponse> read(Path dir, String hash) {
    Path file = file(dir, hash);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    JsonNode doc;
    try {
      doc = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read replay fixture " + file, e);
    }
    if (!hash.equals(doc.path("requestHash").asString(""))) {
      throw new IllegalStateException(file + " is not the fixture for request " + hash);
    }
    JsonNode res = doc.path("response");
    return Optional.of(
        new LlmResponse(
            res.path("text").asString(""),
            Optional.empty(),
            res.path("inputTokens").asLong(0),
            res.path("outputTokens").asLong(0),
            res.path("model").asString("replay"),
            Duration.ofMillis(res.path("latencyMs").asLong(0))));
  }
}
