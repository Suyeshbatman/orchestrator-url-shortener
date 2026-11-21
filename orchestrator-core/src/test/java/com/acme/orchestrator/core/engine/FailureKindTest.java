package com.acme.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class FailureKindTest {

  @Test
  void exceptionsAreClassified() {
    assertThat(FailureKind.of(new TransientAgentException("429"))).isEqualTo(FailureKind.TRANSIENT);
    assertThat(FailureKind.of(new TimeoutException())).isEqualTo(FailureKind.TRANSIENT);
    assertThat(FailureKind.of(new IOException("reset"))).isEqualTo(FailureKind.TRANSIENT);
    assertThat(FailureKind.of(new UncheckedIOException(new IOException("reset"))))
        .isEqualTo(FailureKind.TRANSIENT);
    assertThat(FailureKind.of(new IllegalStateException("no spec"))).isEqualTo(FailureKind.FATAL);
    assertThat(FailureKind.of(new IllegalArgumentException("bad"))).isEqualTo(FailureKind.FATAL);
    assertThat(FailureKind.of(new RuntimeException("unknown"))).isEqualTo(FailureKind.TRANSIENT);
  }

  @Test
  void onlyFatalIsNotRetryable() {
    assertThat(FailureKind.TRANSIENT.retryable()).isTrue();
    assertThat(FailureKind.VALIDATION.retryable()).isTrue();
    assertThat(FailureKind.FATAL.retryable()).isFalse();
  }

  @Test
  void agentFailuresDefaultToValidation() {
    assertThat(AgentResult.failure("missing ids").kind()).isEqualTo(FailureKind.VALIDATION);
    assertThat(AgentResult.failure("x", FailureKind.FATAL).kind()).isEqualTo(FailureKind.FATAL);
  }
}
