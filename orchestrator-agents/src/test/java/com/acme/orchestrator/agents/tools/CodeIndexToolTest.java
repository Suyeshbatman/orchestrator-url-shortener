package com.acme.orchestrator.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Indexes the real shortener service, the brownfield scenario's target. */
class CodeIndexToolTest {

  private final CodeIndexTool.RepoMap map =
      new CodeIndexTool(Path.of("..", "shortener-service")).index();

  @Test
  void findsTheRestEndpoints() {
    assertThat(map.restMappings())
        .extracting(r -> r.httpMethod() + " " + r.path())
        .contains(
            "GET /api/v1/links/{code}",
            "DELETE /api/v1/links/{code}",
            "GET /api/v1/links/{code}/stats",
            "GET /{code:[A-Za-z0-9_-]{4,32}}");
    assertThat(map.restMappings())
        .filteredOn(r -> r.path().equals("/api/v1/links/{code}/stats"))
        .singleElement()
        .extracting(CodeIndexTool.RestMapping::file)
        .asString()
        .endsWith("analytics/StatsController.java");
  }

  @Test
  void findsMigrationsPackagesAndPublicTypes() {
    assertThat(map.migrations())
        .contains(
            "src/main/resources/db/migration/V1__init.sql",
            "src/main/resources/db/migration/V2__analytics.sql")
        .isSorted();
    assertThat(map.packages()).contains("com.acme.shortener.link", "com.acme.shortener.analytics");
    assertThat(map.types())
        .filteredOn(t -> t.name().equals("LinkService"))
        .singleElement()
        .satisfies(t -> {
          assertThat(t.kind()).isEqualTo("class");
          assertThat(t.methods()).contains("create", "delete").doesNotContain("LinkService");
        });
    assertThat(map.javaFiles()).noneMatch(f -> f.contains("/target/"));
  }

  @Test
  void rendersACompactOutlineForPrompts() {
    String md = map.toMarkdown();

    assertThat(md)
        .startsWith("# Repository map")
        .contains("## REST endpoints\n- GET")
        .contains("V2__analytics.sql")
        .contains("class com.acme.shortener.link.LinkService: ");
  }
}
