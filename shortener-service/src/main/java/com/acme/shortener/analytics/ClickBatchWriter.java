package com.acme.shortener.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * One background virtual thread: drains the queue in batches of up to {@code batchSize}, or
 * whatever arrived within {@code flushInterval}, and hands them to the {@link ClickStore}. N
 * round-trips become about N/batchSize.
 *
 * <p>Stops after the web server (lower phase), then flushes what is left, so a graceful shutdown
 * loses nothing. A crash loses what is in memory (limitation L-3).
 */
public final class ClickBatchWriter implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ClickBatchWriter.class);

  /** Below the web server's graceful-shutdown phases, so in-flight redirects publish first. */
  static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

  private final BoundedClickQueue queue;
  private final ClickStore store;
  private final int batchSize;
  private final Duration flushInterval;
  private final Counter written;
  private final Counter failed;

  private volatile boolean running;
  private Thread thread;

  public ClickBatchWriter(
      BoundedClickQueue queue,
      ClickStore store,
      int batchSize,
      Duration flushInterval,
      MeterRegistry meters) {
    this.queue = queue;
    this.store = store;
    this.batchSize = batchSize;
    this.flushInterval = flushInterval;
    this.written = Counter.builder("analytics.events.written").register(meters);
    this.failed = Counter.builder("analytics.events.failed").register(meters);
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    thread = Thread.ofVirtual().name("click-batch-writer").start(this::loop);
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    // No interrupt: it could land mid-JDBC and close the connection. The loop notices within one
    // flush interval.
    try {
      thread.join(flushInterval.plusSeconds(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<ClickEvent> rest = new ArrayList<>();
    queue.drainAll(rest);
    for (int from = 0; from < rest.size(); from += batchSize) {
      flush(rest.subList(from, Math.min(rest.size(), from + batchSize)));
    }
    log.info("Click writer stopped; flushed {} pending event(s)", rest.size());
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  private void loop() {
    List<ClickEvent> batch = new ArrayList<>(batchSize);
    while (running) {
      try {
        queue.drain(batch, batchSize, flushInterval);
      } catch (InterruptedException e) {
        running = false; // keep what was drained, flush it and exit
      }
      if (!batch.isEmpty()) {
        flush(batch);
        batch.clear();
      }
    }
  }

  private void flush(List<ClickEvent> batch) {
    try {
      store.write(batch);
      written.increment(batch.size());
    } catch (RuntimeException e) {
      failed.increment(batch.size());
      log.warn("Dropped a batch of {} click(s): {}", batch.size(), e.toString());
    }
  }
}
