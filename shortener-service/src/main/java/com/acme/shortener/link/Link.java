package com.acme.shortener.link;

import java.time.Clock;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A short link. The id comes from {@code link_id_seq} before insert, so Spring Data JDBC decides
 * insert-vs-update from {@code version}: {@code null} means new.
 */
@Table("link")
public record Link(
    @Id Long id,
    String code,
    String originalUrl,
    boolean customAlias,
    LinkStatus status,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    @Version Integer version) {

  public static Link newGenerated(
      long id, String code, String originalUrl, String createdBy, Clock clock) {
    Instant now = clock.instant();
    return new Link(id, code, originalUrl, false, LinkStatus.ACTIVE, createdBy, now, now, null);
  }

  public static Link newAlias(
      long id, String alias, String originalUrl, String createdBy, Clock clock) {
    Instant now = clock.instant();
    return new Link(id, alias, originalUrl, true, LinkStatus.ACTIVE, createdBy, now, now, null);
  }

  public boolean isActive() {
    return status == LinkStatus.ACTIVE;
  }

  /** Soft delete. Idempotent: deleting a deleted link returns it unchanged. */
  public Link markDeleted(Clock clock) {
    if (status == LinkStatus.DELETED) {
      return this;
    }
    return new Link(
        id,
        code,
        originalUrl,
        customAlias,
        LinkStatus.DELETED,
        createdBy,
        createdAt,
        clock.instant(),
        version);
  }

  // Withers used by Spring Data JDBC to populate generated values on an immutable record.
  Link withVersion(Integer version) {
    return new Link(
        id, code, originalUrl, customAlias, status, createdBy, createdAt, updatedAt, version);
  }
}
