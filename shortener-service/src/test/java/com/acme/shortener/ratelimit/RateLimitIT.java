package com.acme.shortener.ratelimit;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.shortener.TestcontainersConfiguration;
import com.acme.shortener.url.HostResolver;
import java.net.InetAddress;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The limits end to end over HTTP with real Redis. Small limits so a burst is a few requests; at
 * these rates a token takes 20s (create) or 12s (redirect) to come back, far longer than the test.
 */
@SpringBootTest(
    properties = {
      "shortener.rate-limit.create-capacity=3",
      "shortener.rate-limit.redirect-capacity=5"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RateLimitIT.PublicDns.class})
class RateLimitIT {

  private static final String KEY = "test-key"; // owner "tester"
  private static final String OTHER_KEY = "other-key"; // owner "other"

  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;

  @TestConfiguration
  static class PublicDns {
    @Bean
    @Primary
    HostResolver fakeHostResolver() throws Exception {
      InetAddress ip = InetAddress.getByName("93.184.215.14");
      return host -> List.of(ip);
    }
  }

  /** Uses up "tester"'s create bucket; no other test creates with that key. */
  @Test
  void createIsLimitedPerApiKeyWith429AndHeaders() throws Exception {
    for (int remaining = 2; remaining >= 0; remaining--) {
      create(KEY)
          .andExpect(status().isCreated())
          .andExpect(header().string("RateLimit-Limit", "3"))
          .andExpect(header().string("RateLimit-Remaining", String.valueOf(remaining)))
          .andExpect(header().exists("RateLimit-Reset"));
    }

    create(KEY)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().string("Retry-After", anyOf(is("19"), is("20"))))
        .andExpect(jsonPath("$.type").value("/problems/rate-limited"))
        .andExpect(jsonPath("$.status").value(429));

    // Another key has its own bucket.
    create(OTHER_KEY).andExpect(status().isCreated());
  }

  @Test
  void redirectIsLimitedPerVisitor() throws Exception {
    String code = code(create(OTHER_KEY).andExpect(status().isCreated()));

    for (int i = 0; i < 5; i++) {
      redirect(code, "203.0.113.7", "Firefox").andExpect(status().isFound());
    }
    redirect(code, "203.0.113.7", "Firefox")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", anyOf(is("11"), is("12"))))
        .andExpect(jsonPath("$.type").value("/problems/rate-limited"));

    // A different user agent or IP is a different visitor.
    redirect(code, "203.0.113.7", "Chrome").andExpect(status().isFound());
    redirect(code, "203.0.113.8", "Firefox").andExpect(status().isFound());
  }

  @Test
  void unlimitedEndpointsHaveNoRateLimitHeaders() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(header().doesNotExist("RateLimit-Limit"))
        .andExpect(status().is(greaterThanOrEqualTo(200)));
  }

  private ResultActions create(String apiKey) throws Exception {
    return mvc.perform(
        post("/api/v1/links")
            .header("X-API-Key", apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.com/rl\"}"));
  }

  private ResultActions redirect(String code, String ip, String userAgent) throws Exception {
    return mvc.perform(
        get("/" + code)
            .header("User-Agent", userAgent)
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            }));
  }

  private String code(ResultActions created) throws Exception {
    return (String)
        json.readValue(created.andReturn().getResponse().getContentAsString(), Map.class)
            .get("code");
  }
}
