package com.acme.shortener.config;

import com.acme.shortener.analytics.BoundedClickQueue;
import com.acme.shortener.analytics.ClickBatchWriter;
import com.acme.shortener.analytics.ClickStatsRepository;
import com.acme.shortener.analytics.JdbcClickStore;
import com.acme.shortener.analytics.StatsService;
import com.acme.shortener.analytics.UniqueVisitors;
import com.acme.shortener.link.LinkService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class AnalyticsConfig {

  @Bean
  BoundedClickQueue clickQueue(ShortenerProperties properties, MeterRegistry meters) {
    return new BoundedClickQueue(properties.analytics().queueCapacity(), meters);
  }

  @Bean
  UniqueVisitors uniqueVisitors(
      StringRedisTemplate redis, CircuitBreaker redisCircuitBreaker, ShortenerProperties p) {
    return new UniqueVisitors(redis, redisCircuitBreaker, p.analytics().hllTtl());
  }

  @Bean
  ClickBatchWriter clickBatchWriter(
      BoundedClickQueue clickQueue,
      NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      UniqueVisitors uniqueVisitors,
      ShortenerProperties properties,
      MeterRegistry meters) {
    ShortenerProperties.Analytics a = properties.analytics();
    return new ClickBatchWriter(
        clickQueue,
        new JdbcClickStore(jdbc, new TransactionTemplate(transactions), uniqueVisitors, meters),
        a.batchSize(),
        a.flushInterval(),
        meters);
  }

  @Bean
  StatsService statsService(
      LinkService links,
      ClickStatsRepository stats,
      UniqueVisitors uniqueVisitors,
      Clock clock,
      ShortenerProperties properties) {
    return new StatsService(
        links, stats, uniqueVisitors, clock, properties.analytics().maxRangeDays());
  }
}
