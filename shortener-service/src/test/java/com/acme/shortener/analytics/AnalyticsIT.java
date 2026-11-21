package com.acme.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.shortener.TestcontainersConfiguration;
import com.acme.shortener.url.HostResolver;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Redirect → queue → batch writer → Postgres + Redis HLL → stats, end to end. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AnalyticsIT.PublicDns.class})
class AnalyticsIT {

  private static final String KEY = "test-key"; // owner "tester"
  private static final String OTHER_KEY = "other-key";

  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  @TestConfiguration
  static class PublicDns {
    @Bean
    @Primary
    HostResolver fakeHostResolver() throws Exception {
      InetAddress ip = InetAddress.getByName("93.184.215.14");
      return host -> List.of(ip);
    }
  }

  @Test
  void hundredRedirectsShowUpAsHundredClicks() throws Exception {
    String code = create();
    String[][] visitors = {
      {"203.0.113.1", "Mozilla/5.0 Chrome/130.0 Safari/537.36"},
      {"203.0.113.2", "Mozilla/5.0 Gecko/20100101 Firefox/132.0"},
      {"203.0.113.3", "Mozilla/5.0 Chrome/130.0 Safari/537.36"},
      {"203.0.113.4", "curl/8.9.1"},
    };
    for (int i = 0; i < 100; i++) {
      String[] v = visitors[i % visitors.length];
      String referer = i < 60 ? "https://news.example.com/item?id=" + i : null;
      var request = get("/" + code).header("User-Agent", v[1]);
      if (referer != null) {
        request.header("Referer", referer);
      }
      mvc.perform(
              request.with(r -> {
                r.setRemoteAddr(v[0]);
                return r;
              }))
          .andExpect(status().isFound());
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> totalClicks(code) == 100); // the writer is ~100ms behind

    String body =
        mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-API-Key", KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.totalClicks").value(100))
            .andExpect(jsonPath("$.uniqueVisitors").value(4))
            .andExpect(jsonPath("$.clicksByDay.length()").value(30))
            .andExpect(jsonPath("$.clicksByDay[29].clicks").value(100))
            .andExpect(jsonPath("$.clicksByDay[0].clicks").value(0))
            .andExpect(jsonPath("$.topReferrers[0].host").value("news.example.com"))
            .andExpect(jsonPath("$.topReferrers[0].clicks").value(60))
            .andExpect(jsonPath("$.byUaFamily.Chrome").value(50))
            .andExpect(jsonPath("$.byUaFamily.Firefox").value(25))
            .andExpect(jsonPath("$.byUaFamily.Bot").value(25))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("203.0.113"); // no raw IPs anywhere in the output

    // Raw rows hold hashes, never IPs; the HLL key has its long TTL.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM click_event e JOIN link l ON l.id = e.link_id"
                    + " WHERE l.code = ? AND length(e.visitor_hash) = 64",
                Long.class,
                code))
        .isEqualTo(100);
    Long linkId = jdbc.queryForObject("SELECT id FROM link WHERE code = ?", Long.class, code);
    String hllKey = UniqueVisitors.key(linkId, LocalDate.now(ZoneOffset.UTC));
    assertThat(redis.getExpire(hllKey)).isGreaterThan(Duration.ofDays(399).toSeconds());
  }

  @Test
  void aLinkWithNoClicksHasEmptyStats() throws Exception {
    String code = create();

    mvc.perform(
            get("/api/v1/links/" + code + "/stats")
                .param("from", "2026-01-01")
                .param("to", "2026-01-07")
                .header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalClicks").value(0))
        .andExpect(jsonPath("$.uniqueVisitors").value(0))
        .andExpect(jsonPath("$.clicksByDay.length()").value(7))
        .andExpect(jsonPath("$.clicksByDay[0].day").value("2026-01-01"))
        .andExpect(jsonPath("$.topReferrers.length()").value(0));
  }

  @Test
  void statsAreOwnerOnly() throws Exception {
    String code = create();

    mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-API-Key", OTHER_KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("/problems/link-not-found"));
    mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalidRangesAre400() throws Exception {
    String code = create();

    mvc.perform(
            get("/api/v1/links/" + code + "/stats")
                .param("from", "2026-01-01")
                .param("to", "2026-06-01")
                .header("X-API-Key", KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/invalid-stats-range"));
    mvc.perform(
            get("/api/v1/links/" + code + "/stats")
                .param("from", "yesterday")
                .header("X-API-Key", KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
  }

  private long totalClicks(String code) {
    Long total =
        jdbc.queryForObject(
            "SELECT coalesce(sum(d.clicks), 0) FROM click_daily d JOIN link l ON l.id = d.link_id"
                + " WHERE l.code = ?",
            Long.class,
            code);
    return total == null ? 0 : total;
  }

  private String create() throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/links")
                    .header("X-API-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/stats\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return (String) json.readValue(response, Map.class).get("code");
  }
}
