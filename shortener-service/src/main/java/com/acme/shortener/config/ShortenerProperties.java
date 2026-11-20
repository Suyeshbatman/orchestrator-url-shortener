package com.acme.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed {@code shortener.*} configuration. Invalid values fail startup. */
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(@Valid Code code, @Valid Alias alias) {

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
