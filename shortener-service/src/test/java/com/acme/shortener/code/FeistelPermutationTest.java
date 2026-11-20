package com.acme.shortener.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.BitSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class FeistelPermutationTest {

  private static final String SECRET = "test-only-feistel-secret-0123456789abcdef";
  private static final long SPACE_7 = Base62Test.SPACE_7;

  private final FeistelPermutation permutation = FeistelPermutation.forCodeLength(SECRET, 7, 4);

  @Property(tries = 10_000)
  void inverseUndoesPermute(@ForAll @LongRange(min = 0, max = SPACE_7 - 1) long x) {
    assertThat(permutation.inverse(permutation.permute(x))).isEqualTo(x);
  }

  @Property(tries = 10_000)
  void permuteUndoesInverse(@ForAll @LongRange(min = 0, max = SPACE_7 - 1) long y) {
    assertThat(permutation.permute(permutation.inverse(y))).isEqualTo(y);
  }

  @Property(tries = 10_000)
  void outputStaysInsideTheCodeSpace(@ForAll @LongRange(min = 0, max = SPACE_7 - 1) long x) {
    assertThat(permutation.permute(x)).isBetween(0L, SPACE_7 - 1);
  }

  /** Exhaustive proof on a small domain (62^2): every output is hit exactly once. */
  @Test
  void isABijectionOnTheWholeDomain() {
    FeistelPermutation small = FeistelPermutation.forCodeLength(SECRET, 2, 4);
    int size = (int) small.domainSize();
    BitSet seen = new BitSet(size);

    for (int x = 0; x < size; x++) {
      long y = small.permute(x);
      assertThat(y).isBetween(0L, size - 1L);
      assertThat(seen.get((int) y)).as("collision at output %d", y).isFalse();
      seen.set((int) y);
    }

    assertThat(seen.cardinality()).isEqualTo(size);
  }

  @Test
  void isDeterministicForTheSameKey() {
    FeistelPermutation again = FeistelPermutation.forCodeLength(SECRET, 7, 4);

    assertThat(again.permute(12_345)).isEqualTo(permutation.permute(12_345));
  }

  @Test
  void differentKeysGiveDifferentPermutations() {
    FeistelPermutation other =
        FeistelPermutation.forCodeLength("another-test-secret-0123456789abcdef", 7, 4);

    int same = 0;
    for (long x = 1; x <= 100; x++) {
      if (other.permute(x) == permutation.permute(x)) {
        same++;
      }
    }
    assertThat(same).isZero();
  }

  @Test
  void rejectsWeakConfiguration() {
    assertThatThrownBy(() -> FeistelPermutation.forCodeLength("too-short", 7, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeistelPermutation.forCodeLength(SECRET, 7, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> permutation.permute(SPACE_7))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> permutation.inverse(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
