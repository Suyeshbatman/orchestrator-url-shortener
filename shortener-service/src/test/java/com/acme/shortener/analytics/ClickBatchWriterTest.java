package com.acme.shortener.analytics;

import static com.acme.shortener.analytics.BoundedClickQueueTest.click;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

class ClickBatchWriterTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final BoundedClickQueue queue = new BoundedClickQueue(10_000, meters);
  private final RecordingStore store = new RecordingStore();
  private ClickBatchWriter writer;

  @AfterEach
  void stop() {
    if (writer != null) {
      writer.stop();
    }
  }

  @Test
  void writesInBatchesOfAtMostBatchSize() {
    for (int i = 0; i < 1_200; i++) {
      queue.publish(click(i));
    }
    writer = start(500, Duration.ofMillis(200));

    await().atMost(Duration.ofSeconds(5)).until(() -> store.total() == 1_200);
    assertThat(store.batches).allSatisfy(b -> assertThat(b).hasSizeLessThanOrEqualTo(500));
    assertThat(store.batches.getFirst()).hasSize(500); // a full queue fills a batch at once
    assertThat(meters.counter("analytics.events.written").count()).isEqualTo(1_200);
  }

  @Test
  void aPartialBatchIsFlushedAfterTheInterval() {
    writer = start(500, Duration.ofMillis(100));

    queue.publish(click(1));
    queue.publish(click(2));

    await().atMost(Duration.ofSeconds(5)).until(() -> store.total() == 2);
    assertThat(store.batches).hasSize(1);
  }

  @Test
  void stopFlushesEverythingPublishedBeforeIt() {
    writer = start(500, Duration.ofMillis(100));
    for (int i = 0; i < 7; i++) {
      queue.publish(click(i));
    }

    writer.stop(); // synchronous: returns after the final flush

    assertThat(store.total()).isEqualTo(7);
    assertThat(writer.isRunning()).isFalse();
  }

  @Test
  void aFailingBatchIsDroppedAndCountedAndTheWriterCarriesOn() {
    store.failNext.set(true);
    writer = start(500, Duration.ofMillis(50));

    queue.publish(click(1));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> meters.counter("analytics.events.failed").count() == 1);

    queue.publish(click(2));
    await().atMost(Duration.ofSeconds(5)).until(() -> store.total() == 1);
    assertThat(store.batches.getFirst()).extracting(ClickEvent::code).containsExactly("code2");
  }

  @Test
  void stopsAfterTheWebServer() {
    // Higher phases stop first; Boot's web server lifecycles sit at DEFAULT_PHASE - 1024 / - 2048.
    assertThat(ClickBatchWriter.PHASE).isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
  }

  private ClickBatchWriter start(int batchSize, Duration flushInterval) {
    ClickBatchWriter w = new ClickBatchWriter(queue, store, batchSize, flushInterval, meters);
    w.start();
    return w;
  }

  static final class RecordingStore implements ClickStore {
    final List<List<ClickEvent>> batches = new CopyOnWriteArrayList<>();
    final AtomicBoolean failNext = new AtomicBoolean();

    @Override
    public void write(List<ClickEvent> batch) {
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("db down");
      }
      batches.add(List.copyOf(batch));
    }

    int total() {
      return batches.stream().mapToInt(List::size).sum();
    }
  }
}
