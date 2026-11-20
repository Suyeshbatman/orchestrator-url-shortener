package com.acme.shortener.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApiKeyRegistryTest {

  private final ApiKeyRegistry registry =
      ApiKeyRegistry.parse(
          "alice:"
              + ApiKeyRegistry.sha256Hex("alice-key")
              + ", bob:"
              + ApiKeyRegistry.sha256Hex("bob-key").toUpperCase());

  @Test
  void authenticatesKnownKeysByName() {
    assertThat(registry.authenticate("alice-key")).contains("alice");
    assertThat(registry.authenticate("bob-key")).contains("bob");
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  void rejectsUnknownMissingAndEmptyKeys() {
    assertThat(registry.authenticate("wrong")).isEmpty();
    assertThat(registry.authenticate(null)).isEmpty();
    assertThat(registry.authenticate("")).isEmpty();
  }

  @Test
  void theHashItselfIsNotAValidKey() {
    assertThat(registry.authenticate(ApiKeyRegistry.sha256Hex("alice-key"))).isEmpty();
  }

  @Test
  void blankConfigMeansNoKeys() {
    assertThat(ApiKeyRegistry.parse("").size()).isZero();
    assertThat(ApiKeyRegistry.parse(null).authenticate("anything")).isEmpty();
  }

  @Test
  void rejectsMalformedConfig() {
    assertThatThrownBy(() -> ApiKeyRegistry.parse("no-colon"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApiKeyRegistry.parse("alice:not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApiKeyRegistry.parse("bad name:" + ApiKeyRegistry.sha256Hex("x")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
