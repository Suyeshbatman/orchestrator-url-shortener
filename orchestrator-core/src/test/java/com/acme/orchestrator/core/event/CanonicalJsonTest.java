package com.acme.orchestrator.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.RunMode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  @Test
  void sortsKeysAtEveryLevelWithoutWhitespace() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("z", 1);
    inner.put("a", List.of(true, "x"));
    Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("b", inner);
    outer.put("a", "first");

    assertThat(CanonicalJson.write(outer))
        .isEqualTo("{\"a\":\"first\",\"b\":{\"a\":[true,\"x\"],\"z\":1}}");
  }

  @Test
  void insertionOrderDoesNotMatter() {
    Map<String, Object> one = new LinkedHashMap<>();
    one.put("x", 1);
    one.put("y", 2);
    Map<String, Object> two = new LinkedHashMap<>();
    two.put("y", 2);
    two.put("x", 1);

    assertThat(CanonicalJson.write(one)).isEqualTo(CanonicalJson.write(two));
  }

  @Test
  void equalNumbersAlwaysLookTheSame() {
    assertThat(CanonicalJson.write(5)).isEqualTo(CanonicalJson.write(5L)).isEqualTo("5");
    assertThat(CanonicalJson.write(new BigDecimal("0.80")))
        .isEqualTo(CanonicalJson.write(0.8d))
        .isEqualTo("0.8");
    assertThat(CanonicalJson.write(2.0d)).isEqualTo("2");
    assertThat(CanonicalJson.write(new BigDecimal("1E+3"))).isEqualTo("1000");
    assertThat(CanonicalJson.write(new BigInteger("123456789012345678901234567890")))
        .isEqualTo("123456789012345678901234567890");
    assertThat(CanonicalJson.write(-0.5f)).isEqualTo("-0.5");
  }

  @Test
  void escapesStringsTheRfc8785Way() {
    assertThat(CanonicalJson.write("a\"b\\c\nd\te\u0001f/é"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001f/é\"");
  }

  @Test
  void convertsConvenienceTypesToStrings() {
    assertThat(CanonicalJson.write(RunMode.BROWNFIELD)).isEqualTo("\"BROWNFIELD\"");
    assertThat(CanonicalJson.write(Duration.ofMinutes(30))).isEqualTo("\"PT30M\"");
    assertThat(CanonicalJson.write(Instant.parse("2026-01-01T00:00:00Z")))
        .isEqualTo("\"2026-01-01T00:00:00Z\"");
  }

  @Test
  void rejectsWhatJsonCantRepresentReproducibly() {
    Map<String, Object> withNull = new HashMap<>();
    withNull.put("k", null);
    Map<Object, Object> intKey = new HashMap<>();
    intKey.put(1, "v");

    assertThatThrownBy(() -> CanonicalJson.write(withNull)).hasMessageContaining("null");
    assertThatThrownBy(() -> CanonicalJson.write(intKey))
        .hasMessageContaining("keys must be strings");
    assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).hasMessageContaining("NaN");
    assertThatThrownBy(() -> CanonicalJson.write(new Object()))
        .hasMessageContaining("unsupported JSON value type");
  }

  @Test
  void normalizedCopiesAreDeepAndImmutable() {
    List<Object> list = new ArrayList<>(List.of("a"));
    Map<String, Object> source = new HashMap<>(Map.of("list", list));

    Map<String, Object> copy = CanonicalJson.normalizeMap(source);
    list.add("b");

    assertThat(copy.get("list")).isEqualTo(List.of("a"));
    assertThatThrownBy(() -> copy.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
  }
}
