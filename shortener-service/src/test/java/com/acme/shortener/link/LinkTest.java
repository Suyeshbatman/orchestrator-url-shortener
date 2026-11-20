package com.acme.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LinkTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

  @Test
  void newGeneratedIsActiveNotAliasAndNew() {
    Link link = Link.newGenerated(1L, "aB3xY9k", "https://a.com", "dev", clockAt(T0));

    assertThat(link.isActive()).isTrue();
    assertThat(link.customAlias()).isFalse();
    assertThat(link.version()).isNull();
    assertThat(link.createdAt()).isEqualTo(T0);
    assertThat(link.updatedAt()).isEqualTo(T0);
  }

  @Test
  void newAliasIsMarkedAsCustomAlias() {
    Link link = Link.newAlias(1L, "my-link", "https://a.com", "dev", clockAt(T0));

    assertThat(link.customAlias()).isTrue();
    assertThat(link.code()).isEqualTo("my-link");
  }

  @Test
  void markDeletedChangesStatusAndUpdatedAtOnly() {
    Link link = Link.newGenerated(1L, "aB3xY9k", "https://a.com", "dev", clockAt(T0));

    Link deleted = link.markDeleted(clockAt(T1));

    assertThat(deleted.status()).isEqualTo(LinkStatus.DELETED);
    assertThat(deleted.isActive()).isFalse();
    assertThat(deleted.updatedAt()).isEqualTo(T1);
    assertThat(deleted.createdAt()).isEqualTo(T0);
    assertThat(deleted)
        .usingRecursiveComparison()
        .ignoringFields("status", "updatedAt")
        .isEqualTo(link);
  }

  @Test
  void markDeletedIsIdempotent() {
    Link deleted =
        Link.newGenerated(1L, "aB3xY9k", "https://a.com", "dev", clockAt(T0))
            .markDeleted(clockAt(T1));

    assertThat(deleted.markDeleted(clockAt(T0))).isSameAs(deleted);
  }

  private static Clock clockAt(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
