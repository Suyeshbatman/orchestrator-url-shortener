package com.acme.orchestrator.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small client for the orchestrator's REST API ({@code /api}, X-API-Key). */
public final class OrchestratorApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI base;
  private final String key;

  public OrchestratorApi(URI base, String key) {
    this.base = base;
    this.key = key;
  }

  public OrchestratorApi as(String otherKey) {
    return new OrchestratorApi(base, otherKey);
  }

  public JsonNode get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(base.resolve(path)).GET(), path);
  }

  public JsonNode post(String path, Object body) throws IOException, InterruptedException {
    String json = body instanceof String s ? s : JSON.writeValueAsString(body);
    return send(
        HttpRequest.newBuilder(base.resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)),
        path);
  }

  /** Whether the app answers its health check (no key needed). */
  public boolean healthy() {
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(base.resolve("/actuator/health"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 && response.body().contains("\"UP\"");
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private JsonNode send(HttpRequest.Builder request, String path)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        http.send(
            request.header("X-API-Key", key).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      JsonNode problem = parse(response.body());
      throw new IOException(
          path
              + " answered "
              + response.statusCode()
              + ": "
              + problem.path("detail").asString(response.body()));
    }
    return parse(response.body());
  }

  private static JsonNode parse(String body) {
    return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  static String json(Map<String, ?> value) {
    return JSON.writeValueAsString(value);
  }
}
