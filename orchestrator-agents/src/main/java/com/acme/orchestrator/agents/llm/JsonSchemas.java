package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.core.gate.SchemaValidator;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON Schemas (draft 2020-12) agents' structured output must match, one per artifact type,
 * loaded from the classpath ({@code schemas/<type>.json}). Also the real implementation of the
 * core's {@link SchemaValidator}, so {@code artifact.schemaValid:<type>} gates check the same
 * schemas the agents were prompted with.
 */
public final class JsonSchemas implements SchemaValidator {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String root;
  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private final Map<String, Optional<Loaded>> cache = new ConcurrentHashMap<>();

  private record Loaded(String text, Schema schema) {}

  /** Schemas under {@code schemas/} on the classpath. */
  public JsonSchemas() {
    this("schemas");
  }

  public JsonSchemas(String classpathRoot) {
    this.root = classpathRoot;
  }

  @Override
  public boolean supports(String artifactType) {
    return load(artifactType).isPresent();
  }

  /** The schema's text, as sent to the model. */
  public String text(String artifactType) {
    return require(artifactType).text();
  }

  @Override
  public List<String> validate(String artifactType, String contentJson) {
    try {
      JSON.readTree(contentJson);
    } catch (JacksonException e) {
      return List.of("not valid JSON: " + e.getOriginalMessage());
    }
    return require(artifactType).schema().validate(contentJson, InputFormat.JSON).stream()
        .map(Error::toString)
        .toList();
  }

  private Loaded require(String artifactType) {
    return load(artifactType)
        .orElseThrow(() ->
            new IllegalArgumentException("no schema for artifact type '" + artifactType + "'"));
  }

  private Optional<Loaded> load(String artifactType) {
    return cache.computeIfAbsent(
        artifactType,
        type -> {
          if (!type.matches("[a-z][a-z0-9_]*")) {
            return Optional.empty();
          }
          String path = root + "/" + type + ".json";
          try (InputStream in = JsonSchemas.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
              return Optional.empty();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(new Loaded(text, registry.getSchema(text)));
          } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema " + path, e);
          }
        });
  }
}
