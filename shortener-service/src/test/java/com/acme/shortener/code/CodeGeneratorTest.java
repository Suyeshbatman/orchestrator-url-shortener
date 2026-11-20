package com.acme.shortener.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

  private static final String SECRET = "test-only-feistel-secret-0123456789abcdef";

  private final AtomicLong sequence = new AtomicLong();
  private final CodeGenerator generator =
      new CodeGenerator(
          sequence::incrementAndGet, FeistelPermutation.forCodeLength(SECRET, 7, 4), 7);

  @Test
  void thousandConsecutiveIdsGiveUniqueNonSequentialCodes() {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      codes.add(generator.next().code());
    }

    assertThat(codes).allSatisfy(c -> assertThat(c).hasSize(7).matches("[0-9a-zA-Z]{7}"));
    Set<String> unique = new HashSet<>(codes);
    assertThat(unique).hasSize(1000);

    // Not enumerable: consecutive ids must not give consecutive (or even ordered) codes.
    int adjacent = 0;
    int ascending = 0;
    for (int i = 1; i < codes.size(); i++) {
      long delta = Base62.decode(codes.get(i)) - Base62.decode(codes.get(i - 1));
      if (Math.abs(delta) == 1) {
        adjacent++;
      }
      if (delta > 0) {
        ascending++;
      }
    }
    assertThat(adjacent).isZero();
    assertThat(ascending).isBetween(400, 600); // ~half, like random order
  }

  @Test
  void nextUsesTheIdSourceAndIsDeterministic() {
    CodeGenerator.GeneratedCode first = generator.next();

    assertThat(first.id()).isEqualTo(1L);
    assertThat(first.code()).isEqualTo(generator.encode(1L));
  }

  @Test
  void rejectsMismatchedPermutationDomain() {
    FeistelPermutation sixChars = FeistelPermutation.forCodeLength(SECRET, 6, 4);

    assertThatThrownBy(() -> new CodeGenerator(sequence::incrementAndGet, sixChars, 7))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
