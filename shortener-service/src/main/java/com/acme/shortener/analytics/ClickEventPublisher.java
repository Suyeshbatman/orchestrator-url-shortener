package com.acme.shortener.analytics;

/**
 * Port: hands a click to the analytics pipeline. Must never block or throw: the redirect path calls
 * it. Delivery is at-most-once (ADR-0004); a durable log (Kafka, Redis Streams) can replace the
 * in-memory queue behind this interface.
 */
public interface ClickEventPublisher {

  /** Returns false if the event was dropped. */
  boolean publish(ClickEvent event);
}
