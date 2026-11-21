package com.acme.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.RetryPolicy.Backoff;
import java.time.Duration;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final Random RANDOM = new Random(42);

  private static RetryPolicy policy(Backoff backoff, boolean jitter) {
    return new RetryPolicy(5, backoff, Duration.ofSeconds(2), Duration.ofSeconds(20), jitter);
  }

  @Test
  void exponentialDoublesUpToTheCap() {
    RetryPolicy p = policy(Backoff.EXPONENTIAL, false);

    assertThat(p.delayAfter(1, RANDOM)).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.delayAfter(2, RANDOM)).isEqualTo(Duration.ofSeconds(4));
    assertThat(p.delayAfter(3, RANDOM)).isEqualTo(Duration.ofSeconds(8));
    assertThat(p.delayAfter(4, RANDOM)).isEqualTo(Duration.ofSeconds(16));
    assertThat(p.delayAfter(5, RANDOM)).isEqualTo(Duration.ofSeconds(20));
    assertThat(p.delayAfter(1_000, RANDOM)).isEqualTo(Duration.ofSeconds(20)); // no overflow
  }

  @Test
  void fixedAlwaysWaitsTheBaseDelay() {
    RetryPolicy p = policy(Backoff.FIXED, false);

    assertThat(p.delayAfter(1, RANDOM)).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.delayAfter(4, RANDOM)).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void zeroDelayStaysZeroEvenWithJitter() {
    RetryPolicy p = new RetryPolicy(3, Backoff.EXPONENTIAL, Duration.ZERO, Duration.ZERO, true);

    assertThat(p.delayAfter(2, RANDOM)).isZero();
  }

  @Test
  void attemptsUsedStartsAtOne() {
    assertThatThrownBy(() -> RetryPolicy.NONE.delayAfter(0, RANDOM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void fullJitterStaysBetweenZeroAndTheCappedDelay(
      @ForAll @IntRange(min = 1, max = 64) int attemptsUsed, @ForAll @LongRange long seed) {
    RetryPolicy p = policy(Backoff.EXPONENTIAL, true);
    Duration ceiling = policy(Backoff.EXPONENTIAL, false).delayAfter(attemptsUsed, RANDOM);

    Duration delay = p.delayAfter(attemptsUsed, new Random(seed));

    assertThat(delay).isBetween(Duration.ZERO, ceiling);
  }
}
