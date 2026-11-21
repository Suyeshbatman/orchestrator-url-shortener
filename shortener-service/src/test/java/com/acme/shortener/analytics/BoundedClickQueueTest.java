package com.acme.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedClickQueueTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final BoundedClickQueue queue = new BoundedClickQueue(3, meters);

  static ClickEvent click(int i) {
    return new ClickEvent(
        "code" + i, Instant.parse("2026-01-01T00:00:00Z"), null, UaFamily.OTHER, "h" + i);
  }

  @Test
  void whenFullItDropsAndCountsInsteadOfBlocking() {
    for (int i = 0; i < 3; i++) {
      assertThat(queue.publish(click(i))).isTrue();
    }

    assertThat(queue.publish(click(3))).isFalse();
    assertThat(queue.publish(click(4))).isFalse();

    assertThat(queue.size()).isEqualTo(3);
    assertThat(meters.counter("analytics.events.dropped").count()).isEqualTo(2);
    assertThat(meters.counter("analytics.events.published").count()).isEqualTo(3);
    assertThat(meters.get("analytics.queue.size").gauge().value()).isEqualTo(3);
  }

  @Test
  void drainStopsAtMaxWithoutWaiting() throws Exception {
    queue.publish(click(0));
    queue.publish(click(1));
    queue.publish(click(2));
    List<ClickEvent> into = new ArrayList<>();

    assertThat(queue.drain(into, 2, Duration.ofMinutes(10))).isEqualTo(2); // returns at once
    assertThat(into).extracting(ClickEvent::code).containsExactly("code0", "code1");
    assertThat(queue.size()).isEqualTo(1);
  }

  @Test
  void drainReturnsWhatArrivedWhenTheWaitEnds() throws Exception {
    queue.publish(click(0));
    List<ClickEvent> into = new ArrayList<>();

    assertThat(queue.drain(into, 500, Duration.ofMillis(50))).isEqualTo(1);
  }

  @Test
  void drainAllEmptiesTheQueue() {
    queue.publish(click(0));
    queue.publish(click(1));
    List<ClickEvent> into = new ArrayList<>();

    assertThat(queue.drainAll(into)).isEqualTo(2);
    assertThat(queue.size()).isZero();
  }
}
