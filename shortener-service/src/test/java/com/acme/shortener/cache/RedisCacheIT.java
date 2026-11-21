package com.acme.shortener.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.shortener.link.CreateLinkRequest;
import com.acme.shortener.link.LinkNotFoundException;
import com.acme.shortener.link.LinkService;
import com.acme.shortener.url.HostResolver;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The cache against real Redis and Postgres. Owns its containers (and a dirty context) because the
 * last test stops Redis.
 */
@SpringBootTest(properties = "shortener.cache.redis-breaker.wait-duration-in-open-state=10m")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCacheIT {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  @Container
  @ServiceConnection(name = "redis")
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @TestConfiguration
  static class PublicDns {
    @Bean
    @Primary
    HostResolver fakeHostResolver() throws Exception {
      InetAddress ip = InetAddress.getByName("93.184.215.14");
      return host -> List.of(ip);
    }
  }

  @Autowired LinkService links;
  @Autowired TieredLinkCache cache;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired CircuitBreaker redisCircuitBreaker;
  @Autowired MeterRegistry meters;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @Test
  @Order(1)
  void createWritesRedisWithTtl() {
    String code = links.create(new CreateLinkRequest("https://example.com/r1", null), "it").code();

    assertThat(redisTemplate.opsForValue().get("link:" + code))
        .isEqualTo("U:https://example.com/r1");
    Long ttl = redisTemplate.getExpire("link:" + code);
    assertThat(ttl).isBetween(23 * 3600L, 24 * 3600L);
  }

  @Test
  @Order(2)
  void redirectIsServedFromRedisWhenL1IsCold() {
    String code = links.create(new CreateLinkRequest("https://example.com/r2", null), "it").code();
    cache.invalidateAllLocal();
    // Prove the DB isn't consulted: change the row behind the cache's back.
    jdbc.update("UPDATE link SET original_url = 'https://changed.example' WHERE code = ?", code);

    assertThat(links.resolve(code)).isEqualTo(URI.create("https://example.com/r2"));
  }

  @Test
  @Order(3)
  void unknownCodeIsNegativelyCachedInRedis() {
    assertThatThrownBy(() -> links.resolve("unknwn1")).isInstanceOf(LinkNotFoundException.class);

    assertThat(redisTemplate.opsForValue().get("link:unknwn1")).isEqualTo("N");
    assertThat(redisTemplate.getExpire("link:unknwn1")).isBetween(1L, 60L);
  }

  /** NFR-2: with Redis gone, redirects still work from the DB and the breaker opens. */
  @Test
  @Order(4)
  void redisDownFallsBackToTheDatabase() {
    String code = links.create(new CreateLinkRequest("https://example.com/r4", null), "it").code();
    double fallbacksBefore = meters.counter("cache.redis.fallback").count();

    redis.stop();
    cache.invalidateAllLocal();

    for (int i = 0; i < 15; i++) {
      cache.invalidateAllLocal(); // force the Redis path every time
      assertThat(links.resolve(code)).isEqualTo(URI.create("https://example.com/r4"));
    }

    assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(meters.counter("cache.redis.fallback").count()).isGreaterThan(fallbacksBefore);

    long start = System.nanoTime();
    cache.invalidateAllLocal();
    links.resolve(code);
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(150); // no Redis timeout paid
  }

  /** Runs after Redis was stopped: the service reports DEGRADED (200), and stays ready. */
  @Test
  @Order(5)
  void redisDownIsDegradedNotDown() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(jsonPath("$.components.redis.status").value("DEGRADED"))
        .andExpect(jsonPath("$.components.db.status").value("UP"));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
