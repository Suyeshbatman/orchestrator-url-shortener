package com.acme.shortener.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.shortener.idempotency.IdempotencyService.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-02T00:00:00Z");

  private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
  private final IdempotencyService service;
  private final AtomicInteger calls = new AtomicInteger();

  IdempotencyServiceTest() {
    PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service =
        new IdempotencyService(
            repository,
            new TransactionTemplate(tx),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(24));
  }

  private StoredResponse action() {
    calls.incrementAndGet();
    return new StoredResponse(201, "{\"code\":\"abc\"}");
  }

  @Test
  void firstUseRunsTheActionAndStoresTheResponse() {
    when(repository.tryClaim(anyString(), eq("alice"), eq("hash1"), eq(NOW))).thenReturn(true);

    IdempotencyService.Result result = service.execute("alice", "key-1", "hash1", this::action);

    assertThat(result.replayed()).isFalse();
    assertThat(result.response().status()).isEqualTo(201);
    assertThat(calls).hasValue(1);
    verify(repository).complete(anyString(), eq(201), eq("{\"code\":\"abc\"}"));
    verify(repository).deleteIfOlderThan(anyString(), eq(NOW.minus(Duration.ofHours(24))));
  }

  @Test
  void sameKeyAndBodyReplaysWithoutRunningTheAction() {
    when(repository.tryClaim(anyString(), any(), any(), any())).thenReturn(false);
    when(repository.find(anyString()))
        .thenReturn(
            Optional.of(new IdempotencyRepository.Record("hash1", 201, "{\"code\":\"abc\"}")));

    IdempotencyService.Result result = service.execute("alice", "key-1", "hash1", this::action);

    assertThat(result.replayed()).isTrue();
    assertThat(result.response().body()).isEqualTo("{\"code\":\"abc\"}");
    assertThat(calls).hasValue(0);
  }

  @Test
  void sameKeyDifferentBodyIsRejected() {
    when(repository.tryClaim(anyString(), any(), any(), any())).thenReturn(false);
    when(repository.find(anyString()))
        .thenReturn(Optional.of(new IdempotencyRepository.Record("hash1", 201, "{}")));

    assertThatThrownBy(() -> service.execute("alice", "key-1", "OTHER", this::action))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(calls).hasValue(0);
  }

  @Test
  void keysAreScopedPerApiKey() {
    when(repository.tryClaim(anyString(), any(), any(), any())).thenReturn(true);
    org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);

    service.execute("alice", "same", "h", this::action);
    service.execute("bob", "same", "h", this::action);

    verify(repository, org.mockito.Mockito.times(2)).tryClaim(keys.capture(), any(), any(), any());
    assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1)).hasSize(64);
  }

  @Test
  void failedActionStoresNothing() {
    when(repository.tryClaim(anyString(), any(), any(), any())).thenReturn(true);

    assertThatThrownBy(() ->
            service.execute(
                "alice",
                "key-1",
                "h",
                () -> {
                  throw new IllegalStateException("boom");
                }))
        .hasMessage("boom");
    verify(repository, never()).complete(anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "has space", "tab\tkey", "ümlaut"})
  void rejectsMalformedKeys(String key) {
    assertThatThrownBy(() -> service.execute("alice", key, "h", this::action))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
  }

  @Test
  void rejectsNullAndOverlongKeys() {
    assertThatThrownBy(() -> IdempotencyService.validateKey(null))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
    assertThatThrownBy(() -> IdempotencyService.validateKey("k".repeat(129)))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
    IdempotencyService.validateKey("k".repeat(128));
  }

  @Test
  void purgeDeletesRecordsOlderThanTtl() {
    when(repository.deleteOlderThan(NOW.minus(Duration.ofHours(24)))).thenReturn(3);

    assertThat(service.purgeExpired()).isEqualTo(3);
  }
}
