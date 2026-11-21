package com.acme.shortener.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded in-memory buffer between redirects and the {@link ClickBatchWriter}. Full means drop and
 * count ({@code analytics.events.dropped}): an unbounded queue under a spike ends in an OOM that
 * takes redirects down too.
 */
public final class BoundedClickQueue implements ClickEventPublisher {

  private final ArrayBlockingQueue<ClickEvent> queue;
  private final Counter published;
  private final Counter dropped;

  public BoundedClickQueue(int capacity, MeterRegistry meters) {
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.published = Counter.builder("analytics.events.published").register(meters);
    this.dropped = Counter.builder("analytics.events.dropped").register(meters);
    Gauge.builder("analytics.queue.size", queue, ArrayBlockingQueue::size).register(meters);
  }

  @Override
  public boolean publish(ClickEvent event) {
    if (queue.offer(event)) {
      published.increment();
      return true;
    }
    dropped.increment();
    return false;
  }

  /**
   * Moves up to {@code max} events into {@code into}: waits up to {@code maxWait} for them, but
   * returns as soon as {@code max} is reached.
   */
  int drain(List<ClickEvent> into, int max, Duration maxWait) throws InterruptedException {
    long deadline = System.nanoTime() + maxWait.toNanos();
    int taken = 0;
    while (taken < max) {
      taken += queue.drainTo(into, max - taken);
      if (taken >= max) {
        break;
      }
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        break;
      }
      ClickEvent next = queue.poll(left, TimeUnit.NANOSECONDS);
      if (next == null) {
        break;
      }
      into.add(next);
      taken++;
    }
    return taken;
  }

  /** Everything left, without waiting (shutdown flush). */
  int drainAll(List<ClickEvent> into) {
    return queue.drainTo(into);
  }

  int size() {
    return queue.size();
  }
}
