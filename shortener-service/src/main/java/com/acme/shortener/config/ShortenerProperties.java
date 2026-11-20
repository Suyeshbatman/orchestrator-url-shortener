package com.acme.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed {@code shortener.*} configuration. Invalid values fail startup. */
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
    @NotBlank String baseUrl,
    @DefaultValue("") String apiKeys,
    @Valid Code code,
    @Valid Alias alias,
    @Valid Url url,
    @DefaultValue @Valid Redirect redirect,
    @DefaultValue @Valid Cache cache,
    @DefaultValue @Valid Idempotency idempotency,
    @DefaultValue @Valid RateLimit rateLimit) {

  /** Token buckets: capacity is the burst, capacity/period the long-run rate. */
  public record RateLimit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("60") @Min(1) long createCapacity,
      @DefaultValue("1m") Duration createPeriod,
      @DefaultValue("600") @Min(1) long redirectCapacity,
      @DefaultValue("1m") Duration redirectPeriod,
      @DefaultValue("10000") @Min(1) long localMaxKeys) {}

  public record Redirect(@DefaultValue("302") @Min(301) @Max(308) int status) {}

  public record Cache(
      @DefaultValue("10000") @Min(1) long l1MaxSize,
      @DefaultValue("5m") Duration l1Ttl,
      @DefaultValue("24h") Duration l2Ttl,
      @DefaultValue("60s") Duration negativeTtl,
      @DefaultValue @Valid RedisBreaker redisBreaker) {}

  /** Circuit breaker around every Redis call (cache and, later, rate limiter). */
  public record RedisBreaker(
      @DefaultValue("20") @Min(1) int slidingWindowSize,
      @DefaultValue("10") @Min(1) int minimumNumberOfCalls,
      @DefaultValue("50") @Min(1) @Max(100) float failureRateThreshold,
      @DefaultValue("10s") Duration waitDurationInOpenState,
      @DefaultValue("3") @Min(1) int permittedCallsInHalfOpenState) {}

  public record Idempotency(@DefaultValue("24h") Duration ttl) {}

  public record Url(
      @DefaultValue("2048") @Min(16) int maxLength,
      @DefaultValue({"http", "https"}) Set<String> allowedSchemes,
      @DefaultValue("true") boolean blockPrivateNetworks,
      @DefaultValue("500ms") Duration dnsTimeout) {}

  /**
   * @param secret Feistel key (env SHORTENER_CODE_SECRET), at least 32 characters
   */
  public record Code(
      @DefaultValue("7") @Min(4) @Max(10) int length,
      @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
      @DefaultValue("4") @Min(3) int feistelRounds) {}

  public record Alias(
      @DefaultValue("4") @Min(1) int min,
      @DefaultValue("32") @Max(128) int max,
      @DefaultValue("^[A-Za-z0-9_-]+$") String pattern,
      @DefaultValue({"api", "actuator", "admin", "health", "swagger-ui"}) Set<String> reserved) {}
}
