package com.acme.shortener.idempotency;

import com.acme.shortener.auth.ApiKeyRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent execution keyed by (API key, Idempotency-Key) for 24h (DESIGN_RATIONALE §3.7).
 *
 * <p>Claim, action and stored response share one transaction. A concurrent duplicate blocks on the
 * claim insert until the first commits, then replays its response. If the action fails, the claim
 * rolls back with it and the client may retry with the same key. Only successful responses are
 * stored.
 */
public class IdempotencyService {

  private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

  /** The response to send; {@code body} is JSON. */
  public record StoredResponse(int status, String body) {}

  public record Result(StoredResponse response, boolean replayed) {}

  private final IdempotencyRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Duration ttl;

  public IdempotencyService(
      IdempotencyRepository repository,
      TransactionTemplate transactions,
      Clock clock,
      Duration ttl) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
    this.ttl = ttl;
  }

  public static void validateKey(String key) {
    if (key == null || !VALID_KEY.matcher(key).matches()) {
      throw new InvalidIdempotencyKeyException();
    }
  }

  public Result execute(
      String apiKeyName, String key, String requestHash, Supplier<StoredResponse> action) {
    validateKey(key);
    String storageKey = ApiKeyRegistry.sha256Hex(apiKeyName + ":" + key);
    return transactions.execute(status -> {
      Instant now = clock.instant();
      repository.deleteIfOlderThan(storageKey, now.minus(ttl)); // expired: key is free again
      if (repository.tryClaim(storageKey, apiKeyName, requestHash, now)) {
        StoredResponse response = action.get();
        repository.complete(storageKey, response.status(), response.body());
        return new Result(response, false);
      }
      IdempotencyRepository.Record existing =
          repository
              .find(storageKey)
              .orElseThrow(() -> new IllegalStateException("idempotency record vanished"));
      if (!existing.requestHash().equals(requestHash)) {
        throw new IdempotencyKeyReusedException();
      }
      if (existing.responseStatus() == null) {
        throw new IllegalStateException("idempotency record has no stored response");
      }
      return new Result(
          new StoredResponse(existing.responseStatus(), existing.responseBody()), true);
    });
  }

  /** Removes all expired records. Returns how many were deleted. */
  public int purgeExpired() {
    return repository.deleteOlderThan(clock.instant().minus(ttl));
  }
}
