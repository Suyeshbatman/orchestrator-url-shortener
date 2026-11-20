package com.acme.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.shortener.TestcontainersConfiguration;
import com.acme.shortener.url.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end API contract over real Postgres (Testcontainers): every endpoint, every Problem
 * Details type, auth/ownership and idempotency. Also exports the OpenAPI spec.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LinkApiIT.FakeDns.class})
class LinkApiIT {

  private static final String KEY = "test-key"; // owner "tester", see test config
  private static final String OTHER_KEY = "other-key"; // owner "other"
  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @Autowired com.acme.shortener.cache.LinkCache cache;

  /** Public DNS answers without the network; "internal.test" resolves to a private address. */
  @TestConfiguration
  static class FakeDns {
    @Bean
    @Primary
    HostResolver fakeHostResolver() {
      return host -> {
        String ip =
            switch (host) {
              case "internal.test" -> "10.0.0.7";
              case "nxdomain.test" -> throw new UnknownHostException(host);
              default -> "93.184.215.14";
            };
        return List.of(InetAddress.getByName(ip));
      };
    }
  }

  // ---- create ----

  @Test
  void createReturns201WithLocationAndBody() throws Exception {
    mvc.perform(create("{\"url\":\"https://example.com/a\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", startsWith("/api/v1/links/")))
        .andExpect(header().string("Idempotent-Replayed", "false"))
        .andExpect(jsonPath("$.code").isString())
        .andExpect(jsonPath("$.shortUrl", startsWith("http://sho.rt/")))
        .andExpect(jsonPath("$.originalUrl").value("https://example.com/a"))
        .andExpect(jsonPath("$.customAlias").value(false))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.createdAt").isString());
  }

  @Test
  void createWithAliasThenSameAliasInOtherCaseIs409() throws Exception {
    String alias = "Promo-" + suffix();
    mvc.perform(create(body("https://example.com", alias)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(alias))
        .andExpect(jsonPath("$.customAlias").value(true));

    expectProblem(
        mvc.perform(create(body("https://example.com", alias.toLowerCase()))), 409, "alias-taken");
  }

  @Test
  void invalidAliasIs400() throws Exception {
    expectProblem(mvc.perform(create(body("https://example.com", "api"))), 400, "invalid-alias");
    expectProblem(mvc.perform(create(body("https://example.com", "ab"))), 400, "invalid-alias");
  }

  @Test
  void invalidUrlIs400WithReason() throws Exception {
    expectProblem(mvc.perform(create(body("http://127.0.0.1/admin", null))), 400, "invalid-url")
        .andExpect(jsonPath("$.reason").value("PRIVATE_ADDRESS"));
    expectProblem(mvc.perform(create(body("http://internal.test", null))), 400, "invalid-url")
        .andExpect(jsonPath("$.reason").value("PRIVATE_ADDRESS"));
    expectProblem(mvc.perform(create(body("javascript:alert(1)", null))), 400, "invalid-url")
        .andExpect(jsonPath("$.reason").value("SCHEME_NOT_ALLOWED"));
    expectProblem(mvc.perform(create(body("https://nxdomain.test", null))), 400, "invalid-url")
        .andExpect(jsonPath("$.reason").value("UNRESOLVABLE_HOST"));
  }

  @Test
  void missingUrlIsValidationFailedWithFieldErrors() throws Exception {
    expectProblem(mvc.perform(create("{\"customAlias\":\"abcd\"}")), 400, "validation-failed")
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("url"));
  }

  @Test
  void unparseableBodyIsMalformedRequest() throws Exception {
    expectProblem(mvc.perform(create("{not json")), 400, "malformed-request");
    expectProblem(mvc.perform(create("")), 400, "malformed-request");
  }

  // ---- auth ----

  @Test
  void managementEndpointsRequireAValidApiKey() throws Exception {
    MockHttpServletRequestBuilder noKey =
        post("/api/v1/links")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.com\"}");
    expectProblem(mvc.perform(noKey), 401, "unauthorized")
        .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""));
    expectProblem(
        mvc.perform(get("/api/v1/links/abcd123").header("X-API-Key", "wrong")),
        401,
        "unauthorized");
    expectProblem(mvc.perform(delete("/api/v1/links/abcd123")), 401, "unauthorized");
  }

  @Test
  void otherApiKeysCannotSeeOrDeleteMyLinks() throws Exception {
    String code = createLink("https://example.com/mine");

    expectProblem(
        mvc.perform(get("/api/v1/links/" + code).header("X-API-Key", OTHER_KEY)),
        404,
        "link-not-found");
    expectProblem(
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", OTHER_KEY)),
        404,
        "link-not-found");
    mvc.perform(get("/" + code)).andExpect(status().isFound()); // still active
  }

  // ---- redirect, metadata, delete ----

  @Test
  void redirectsWith302AndNoCaching() throws Exception {
    String code = createLink("https://example.com/target?x=1");

    mvc.perform(get("/" + code))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/target?x=1"))
        .andExpect(header().string("Cache-Control", "max-age=0, private"));
  }

  @Test
  void unknownCodeRedirectIs404Problem() throws Exception {
    expectProblem(mvc.perform(get("/zzzzzzz")), 404, "link-not-found");
  }

  @Test
  void redirectPatternDoesNotShadowActuatorOrDocs() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  void getReturnsMetadata() throws Exception {
    String code = createLink("https://example.com/meta");

    mvc.perform(get("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.originalUrl").value("https://example.com/meta"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void createAndGetReturnIdenticalBodies() throws Exception {
    String created =
        mvc.perform(create(body("https://example.com/same", null)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String code = (String) json.readValue(created, Map.class).get("code");

    mvc.perform(get("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(content().json(created, org.springframework.test.json.JsonCompareMode.STRICT));
  }

  @Test
  void deleteIsSoftAndIdempotent() throws Exception {
    String code = createLink("https://example.com/del");

    mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DELETED"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link WHERE code = ?", Integer.class, code))
        .isEqualTo(1);
  }

  /**
   * Pins the seeded defect BF-1 (docs/known-issues.md): the link was cached on create and delete
   * does not evict, so it keeps redirecting. The brownfield fix must flip this to 404.
   */
  @Test
  void knownIssueBf1_deletedLinkStillRedirectsFromCache() throws Exception {
    String code = createLink("https://example.com/bf1");

    mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isNoContent());

    mvc.perform(get("/" + code))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/bf1"));
  }

  @Test
  void deletedLinkIs404OnceNotCached() throws Exception {
    String code = createLink("https://example.com/del-cold");
    mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", KEY))
        .andExpect(status().isNoContent());
    cache.evict(code); // simulate the cache entry having expired

    expectProblem(mvc.perform(get("/" + code)), 404, "link-not-found");
  }

  @Test
  void deleteUnknownIs404() throws Exception {
    expectProblem(
        mvc.perform(delete("/api/v1/links/nope404").header("X-API-Key", KEY)),
        404,
        "link-not-found");
  }

  // ---- idempotency ----

  @Test
  void sameIdempotencyKeyAndBodyReplaysTheFirstResponse() throws Exception {
    String key = "idem-" + suffix();
    String body = "{\"url\":\"https://example.com/idem\"}";

    MvcResult first =
        mvc.perform(create(body).header("Idempotency-Key", key))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andReturn();
    mvc.perform(create(body).header("Idempotency-Key", key))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(header().string("Location", first.getResponse().getHeader("Location")))
        .andExpect(content().json(first.getResponse().getContentAsString()));

    assertThat(countLinks("https://example.com/idem")).isEqualTo(1);
  }

  @Test
  void sameIdempotencyKeyDifferentBodyIs422() throws Exception {
    String key = "idem-" + suffix();
    mvc.perform(create("{\"url\":\"https://example.com/one\"}").header("Idempotency-Key", key))
        .andExpect(status().isCreated());

    expectProblem(
        mvc.perform(create("{\"url\":\"https://example.com/two\"}").header("Idempotency-Key", key)),
        422,
        "idempotency-key-reused");
  }

  @Test
  void idempotencyKeysAreScopedPerApiKey() throws Exception {
    String key = "idem-" + suffix();
    mvc.perform(create("{\"url\":\"https://example.com/scope-a\"}").header("Idempotency-Key", key))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/links")
                .header("X-API-Key", OTHER_KEY)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/scope-b\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "false"));
  }

  @Test
  void failedCreateDoesNotBurnTheIdempotencyKey() throws Exception {
    String key = "idem-" + suffix();
    expectProblem(
        mvc.perform(create(body("http://127.0.0.1", null)).header("Idempotency-Key", key)),
        400,
        "invalid-url");

    mvc.perform(create(body("http://127.0.0.1", null)).header("Idempotency-Key", key))
        .andExpect(status().isBadRequest()); // still evaluated, not 422
    mvc.perform(
            create("{\"url\":\"https://example.com/after-fail\"}").header("Idempotency-Key", key))
        .andExpect(status().isCreated());
  }

  @Test
  void malformedIdempotencyKeyIs400() throws Exception {
    expectProblem(
        mvc.perform(
            create("{\"url\":\"https://example.com\"}").header("Idempotency-Key", "k".repeat(129))),
        400,
        "invalid-idempotency-key");
  }

  @Test
  void concurrentDuplicatesCreateExactlyOneLink() throws Exception {
    String key = "idem-" + suffix();
    String url = "https://example.com/race-" + suffix();
    String body = "{\"url\":\"" + url + "\"}";
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MvcResult>> results = new ArrayList<>();

    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        Callable<MvcResult> call =
            () -> {
              start.await();
              return mvc.perform(create(body).header("Idempotency-Key", key)).andReturn();
            };
        results.add(pool.submit(call));
      }
      start.countDown();

      List<String> codes = new ArrayList<>();
      int fresh = 0;
      for (Future<MvcResult> f : results) {
        MvcResult r = f.get();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        if ("false".equals(r.getResponse().getHeader("Idempotent-Replayed"))) {
          fresh++;
        }
        Map<?, ?> parsed = json.readValue(r.getResponse().getContentAsString(), Map.class);
        codes.add((String) parsed.get("code"));
      }
      assertThat(fresh).isEqualTo(1);
      assertThat(codes).containsOnly(codes.get(0));
    }
    assertThat(countLinks(url)).isEqualTo(1);
  }

  // ---- OpenAPI ----

  @Test
  void exportsOpenApiSpec() throws Exception {
    String yaml =
        mvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(yaml).contains("/api/v1/links", "/{code}", "X-API-Key", "Idempotency-Key");
    Path target = Path.of("src/main/resources/static/openapi.yaml");
    Files.createDirectories(target.getParent());
    Files.writeString(target, yaml.replace("\r\n", "\n"));
  }

  // ---- helpers ----

  private static MockHttpServletRequestBuilder create(String body) {
    return post("/api/v1/links")
        .header("X-API-Key", KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String body(String url, String alias) {
    return alias == null
        ? "{\"url\":\"" + url + "\"}"
        : "{\"url\":\"" + url + "\",\"customAlias\":\"" + alias + "\"}";
  }

  private String createLink(String url) throws Exception {
    String response =
        mvc.perform(create(body(url, null)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return (String) json.readValue(response, Map.class).get("code");
  }

  private org.springframework.test.web.servlet.ResultActions expectProblem(
      org.springframework.test.web.servlet.ResultActions actions, int status, String slug)
      throws Exception {
    return actions
        .andExpect(status().is(status))
        .andExpect(header().string("Content-Type", containsString(PROBLEM_JSON)))
        .andExpect(jsonPath("$.type").value("/problems/" + slug))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").isString())
        .andExpect(jsonPath("$.detail").isString())
        .andExpect(jsonPath("$.instance").isString());
  }

  private int countLinks(String url) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM link WHERE original_url = ?", Integer.class, url);
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
