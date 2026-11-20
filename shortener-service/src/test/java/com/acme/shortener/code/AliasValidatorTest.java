package com.acme.shortener.code;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AliasValidatorTest {

  private final Set<String> existing = Set.of("taken");
  private final AliasValidator validator =
      new AliasValidator(
          4,
          32,
          "^[A-Za-z0-9_-]+$",
          Set.of("api", "actuator", "admin", "health", "swagger-ui"),
          alias -> existing.contains(alias.toLowerCase()));

  @ParameterizedTest
  @ValueSource(strings = {"my-link", "Promo_2026", "abcd", "a234567890123456789012345678901b"})
  void acceptsValidAliases(String alias) {
    assertThatCode(() -> validator.validate(alias)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "a2345678901234567890123456789012x", ""})
  void rejectsBadLength(String alias) {
    assertThatThrownBy(() -> validator.validate(alias))
        .isInstanceOf(InvalidAliasException.class)
        .hasMessageContaining("4-32");
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(InvalidAliasException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"has space", "slash/es", "dot.dot", "ümlaut", "<script>"})
  void rejectsBadCharacters(String alias) {
    assertThatThrownBy(() -> validator.validate(alias))
        .isInstanceOf(InvalidAliasException.class)
        .hasMessageContaining("letters, digits");
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin", "ADMIN", "Health", "swagger-ui"})
  void rejectsReservedWordsIgnoringCase(String alias) {
    assertThatThrownBy(() -> validator.validate(alias))
        .isInstanceOf(InvalidAliasException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void rejectsTakenAliasIgnoringCase() {
    assertThatThrownBy(() -> validator.validate("TaKeN")).isInstanceOf(AliasTakenException.class);
  }
}
