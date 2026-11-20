package com.acme.shortener.config;

import com.acme.shortener.idempotency.IdempotencyRepository;
import com.acme.shortener.idempotency.IdempotencyService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AppConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  IdempotencyService idempotencyService(
      IdempotencyRepository repository,
      TransactionTemplate transactions,
      Clock clock,
      ShortenerProperties properties) {
    return new IdempotencyService(repository, transactions, clock, properties.idempotency().ttl());
  }
}
