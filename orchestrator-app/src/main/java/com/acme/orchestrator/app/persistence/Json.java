package com.acme.orchestrator.app.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON and time conversions shared by the JDBC adapters. */
final class Json {

  /** Numbers come back as Long / BigDecimal, the types the canonical form was hashed with. */
  static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_LONG_FOR_INTS)
          .build();

  private Json() {}

  static Map<String, Object> map(String json) {
    return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
