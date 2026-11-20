package com.acme.shortener.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly purge of expired idempotency records. */
@Component
class IdempotencyCleanup {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

  private final IdempotencyService service;

  IdempotencyCleanup(IdempotencyService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${shortener.idempotency.cleanup-interval:PT1H}")
  void purge() {
    int deleted = service.purgeExpired();
    if (deleted > 0) {
      log.info("Purged {} expired idempotency records", deleted);
    }
  }
}
