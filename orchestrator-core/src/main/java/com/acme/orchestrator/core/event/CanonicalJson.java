package com.acme.orchestrator.core.event;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one serialization that gets hashed, so it must never vary: object keys sorted (by UTF-16 code
 * units, as RFC 8785), no whitespace, fixed string escaping, numbers in one form. Values are first
 * {@link #normalize normalized} to a small JSON data model, so the same data always gives the same
 * bytes, including after a round-trip through a database.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /**
   * Deep, immutable copy restricted to: String, Boolean, Long, BigDecimal (non-integral, trailing
   * zeros stripped), List, Map with String keys. Conveniences: any integral number becomes Long, a
   * decimal with no fraction becomes Long, enums become their name, Instant/Duration/CharSequence
   * become strings. Nulls, NaN/Infinity and anything else are rejected.
   */
  public static Object normalize(Object value) {
    return switch (value) {
      case null -> throw new IllegalArgumentException("null values are not allowed");
      case String s -> s;
      case Boolean b -> b;
      case Byte n -> n.longValue();
      case Short n -> n.longValue();
      case Integer n -> n.longValue();
      case Long n -> n;
      case BigInteger n -> decimal(new BigDecimal(n));
      case BigDecimal n -> decimal(n);
      case Double d -> floating(d);
      case Float f -> floating(f.doubleValue());
      case Enum<?> e -> e.name();
      case Instant i -> i.toString();
      case Duration d -> d.toString();
      case CharSequence cs -> cs.toString();
      case List<?> list -> {
        List<Object> copy = new ArrayList<>(list.size());
        list.forEach(v -> copy.add(normalize(v)));
        yield Collections.unmodifiableList(copy);
      }
      case Map<?, ?> map -> normalizeMap(map);
      default ->
          throw new IllegalArgumentException(
              "unsupported JSON value type: " + value.getClass().getName());
    };
  }

  /** {@link #normalize} for an object; returns an unmodifiable map that keeps insertion order. */
  public static Map<String, Object> normalizeMap(Map<?, ?> map) {
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((k, v) -> {
      if (!(k instanceof String key)) {
        throw new IllegalArgumentException("object keys must be strings: " + k);
      }
      copy.put(key, normalize(v));
    });
    return Collections.unmodifiableMap(copy);
  }

  /** Canonical text of a value (normalized first). */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeNormalized(normalize(value), out);
    return out.toString();
  }

  private static void writeNormalized(Object value, StringBuilder out) {
    switch (value) {
      case String s -> string(s, out);
      case Boolean b -> out.append(b);
      case Long n -> out.append(n);
      case BigDecimal n -> out.append(n.toPlainString());
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          writeNormalized(list.get(i), out);
        }
        out.append(']');
      }
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : new TreeMap<>(map).entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          string((String) e.getKey(), out);
          out.append(':');
          writeNormalized(e.getValue(), out);
        }
        out.append('}');
      }
      default -> throw new IllegalStateException("not normalized: " + value.getClass());
    }
  }

  /** RFC 8785 string escaping: short escapes where JSON has them, else a 4-digit hex escape. */
  private static void string(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static Object floating(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("NaN and Infinity are not JSON");
    }
    return decimal(BigDecimal.valueOf(d));
  }

  /** 2.50 → 2.5, 2.0 → 2 (Long), so equal numbers always look the same. */
  private static Object decimal(BigDecimal n) {
    BigDecimal stripped = n.stripTrailingZeros();
    if (stripped.scale() <= 0) {
      try {
        return stripped.longValueExact();
      } catch (ArithmeticException tooBig) {
        return stripped.setScale(0); // integral but beyond long: plain digits
      }
    }
    return stripped;
  }
}
