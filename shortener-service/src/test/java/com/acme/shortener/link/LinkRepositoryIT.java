package com.acme.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LinkRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Autowired LinkRepository repository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void insertsNewLinkAndAssignsVersion() {
    long id = repository.nextId();
    Link saved =
        repository.save(Link.newGenerated(id, "aB3xY9k", "https://example.com", "dev", CLOCK));

    assertThat(saved.version()).isZero();
    assertThat(repository.findByCode("aB3xY9k"))
        .get()
        .satisfies(l -> {
          assertThat(l.id()).isEqualTo(id);
          assertThat(l.originalUrl()).isEqualTo("https://example.com");
          assertThat(l.status()).isEqualTo(LinkStatus.ACTIVE);
          assertThat(l.customAlias()).isFalse();
          assertThat(l.createdAt()).isEqualTo(CLOCK.instant());
        });
  }

  @Test
  void updatesExistingLinkAndBumpsVersion() {
    Link saved =
        repository.save(
            Link.newGenerated(repository.nextId(), "upd0001", "https://example.com", "dev", CLOCK));

    Link deleted = repository.save(saved.markDeleted(CLOCK));

    assertThat(deleted.version()).isEqualTo(1);
    assertThat(repository.findByCode("upd0001"))
        .get()
        .extracting(Link::status)
        .isEqualTo(LinkStatus.DELETED);
  }

  @Test
  void rejectsDuplicateCode() {
    repository.save(
        Link.newGenerated(repository.nextId(), "dup0001", "https://a.com", "dev", CLOCK));

    assertThatThrownBy(() ->
            repository.save(
                Link.newGenerated(repository.nextId(), "dup0001", "https://b.com", "dev", CLOCK)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aliasesAreUniqueIgnoringCase() {
    repository.save(Link.newAlias(repository.nextId(), "MyLink", "https://a.com", "dev", CLOCK));

    assertThatThrownBy(() ->
            repository.save(
                Link.newAlias(repository.nextId(), "mylink", "https://b.com", "dev", CLOCK)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void generatedCodesMayDifferOnlyByCase() {
    repository.save(
        Link.newGenerated(repository.nextId(), "abcDEF1", "https://a.com", "dev", CLOCK));
    repository.save(
        Link.newGenerated(repository.nextId(), "ABCdef1", "https://b.com", "dev", CLOCK));

    assertThat(repository.findByCode("abcDEF1")).isPresent();
    assertThat(repository.findByCode("ABCdef1")).isPresent();
  }

  @Test
  void existsByCodeIgnoreCase() {
    repository.save(
        Link.newGenerated(repository.nextId(), "Case123", "https://a.com", "dev", CLOCK));

    assertThat(repository.existsByCodeIgnoreCase("case123")).isTrue();
    assertThat(repository.existsByCodeIgnoreCase("nope999")).isFalse();
  }

  @Test
  void nextIdIsIncreasing() {
    long first = repository.nextId();
    long second = repository.nextId();

    assertThat(second).isGreaterThan(first);
  }

  @Test
  void statusCheckConstraintRejectsUnknownStatus() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO link (id, code, original_url, status) VALUES (?, ?, ?, ?)",
                repository.nextId(),
                "bad0001",
                "https://a.com",
                "EXPIRED"))
        .isInstanceOf(DataAccessException.class);
  }
}
