package com.acme.shortener.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class Base62Test {

  static final long SPACE_7 = 3_521_614_606_208L; // 62^7

  @Property(tries = 10_000)
  void decodeInvertsEncode(@ForAll @LongRange(min = 0, max = SPACE_7 - 1) long value) {
    String code = Base62.encode(value, 7);

    assertThat(code).hasSize(7);
    assertThat(Base62.decode(code)).isEqualTo(value);
  }

  @Test
  void spaceSizeIs62ToTheLength() {
    assertThat(Base62.spaceSize(1)).isEqualTo(62);
    assertThat(Base62.spaceSize(7)).isEqualTo(SPACE_7);
  }

  @Test
  void encodesKnownValuesWithLeftPadding() {
    assertThat(Base62.encode(0, 7)).isEqualTo("0000000");
    assertThat(Base62.encode(61, 1)).isEqualTo("Z");
    assertThat(Base62.encode(62, 3)).isEqualTo("010");
    assertThat(Base62.encode(SPACE_7 - 1, 7)).isEqualTo("ZZZZZZZ");
  }

  @Test
  void rejectsValuesOutsideTheCodeSpace() {
    assertThatThrownBy(() -> Base62.encode(-1, 7)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.encode(SPACE_7, 7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> Base62.decode("abc-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.decode(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.decode("12345678901"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Base62.spaceSize(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
