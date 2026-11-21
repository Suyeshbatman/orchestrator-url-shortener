package com.acme.shortener.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Correlation ids, security headers, health groups and metrics over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics // tests disable metric export by default; /actuator/prometheus needs it
@Import(TestcontainersConfiguration.class)
class ObservabilityIT {

  @Autowired MockMvc mvc;

  @Test
  void correlationIdIsEchoedAndPutInProblemDetails() throws Exception {
    mvc.perform(get("/nosuch1").header("X-Correlation-Id", "trace-123"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("X-Correlation-Id", "trace-123"))
        .andExpect(jsonPath("$.correlationId").value("trace-123"));
  }

  @Test
  void aCorrelationIdIsGeneratedWhenAbsent() throws Exception {
    mvc.perform(get("/api/v1/links/abc"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Correlation-Id"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void securityHeadersAreOnEveryResponse() throws Exception {
    mvc.perform(get("/api/v1/links/abc"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CSP))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void livenessAndReadinessAreUpAndReadinessIgnoresRedis() throws Exception {
    mvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components", hasKey("db")))
        .andExpect(jsonPath("$.components", not(hasKey("redis"))));
  }

  @Test
  void overallHealthReportsRedis() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.redis.status").value("UP"));
  }

  @Test
  void timersAndCountersAreExported() throws Exception {
    mvc.perform(get("/nosuch2")).andExpect(status().isNotFound());

    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("shortener_resolve_seconds_bucket")))
        .andExpect(content().string(containsString("outcome=\"not_found\"")))
        .andExpect(content().string(containsString("application=\"shortener-service\"")))
        .andExpect(content().string(containsString("cache_requests_total")))
        .andExpect(content().string(containsString("ratelimit_requests_total")))
        .andExpect(content().string(containsString("analytics_queue_size")));
  }
}
