package com.acme.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.shortener.cache.CacheEntry;
import com.acme.shortener.cache.LinkCache;
import com.acme.shortener.code.AliasTakenException;
import com.acme.shortener.code.AliasValidator;
import com.acme.shortener.code.CodeGenerator;
import com.acme.shortener.code.FeistelPermutation;
import com.acme.shortener.code.InvalidAliasException;
import com.acme.shortener.config.ShortenerProperties;
import com.acme.shortener.url.InvalidUrlException;
import com.acme.shortener.url.UrlValidator;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;

class LinkServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private final LinkRepository repository = mock(LinkRepository.class);
  private final AtomicLong ids = new AtomicLong(100);
  private final CodeGenerator codes =
      new CodeGenerator(
          ids::incrementAndGet,
          FeistelPermutation.forCodeLength("test-only-feistel-secret-0123456789abcdef", 7, 4),
          7);
  private final InMemoryLinkCache cache = new InMemoryLinkCache();
  private LinkService service;

  @BeforeEach
  void setUp() throws Exception {
    InetAddress publicIp = InetAddress.getByName("93.184.215.14");
    UrlValidator urls =
        new UrlValidator(
            2048, Set.of("http", "https"), true, "http://sho.rt", h -> List.of(publicIp));
    AliasValidator aliases =
        new AliasValidator(
            4, 32, "^[A-Za-z0-9_-]+$", Set.of("api"), repository::existsByCodeIgnoreCase);
    ShortenerProperties props =
        new ShortenerProperties("http://sho.rt/", "", null, null, null, null, null, null, null);
    service = new LinkService(repository, codes, aliases, urls, cache, CLOCK, props);

    when(repository.save(any(Link.class))).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findByCode(anyString())).thenReturn(Optional.empty());
    when(repository.nextId()).thenAnswer(inv -> ids.incrementAndGet());
  }

  @Test
  void createsGeneratedLink() {
    LinkResponse response =
        service.create(new CreateLinkRequest("https://example.com/a", null), "alice");

    assertThat(response.code()).isEqualTo(codes.encode(101));
    assertThat(response.shortUrl()).isEqualTo("http://sho.rt/" + response.code());
    assertThat(response.originalUrl()).isEqualTo("https://example.com/a");
    assertThat(response.customAlias()).isFalse();
    assertThat(response.status()).isEqualTo(LinkStatus.ACTIVE);
    assertThat(response.createdAt()).isEqualTo(CLOCK.instant());

    ArgumentCaptor<Link> saved = ArgumentCaptor.forClass(Link.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().createdBy()).isEqualTo("alice");
    assertThat(saved.getValue().id()).isEqualTo(101);
  }

  @Test
  void skipsAGeneratedCodeThatAnAliasAlreadyUses() {
    String clashing = codes.encode(101);
    Link alias = Link.newAlias(1, clashing, "https://x.com", "bob", CLOCK);
    when(repository.findByCode(clashing)).thenReturn(Optional.of(alias));

    LinkResponse response =
        service.create(new CreateLinkRequest("https://example.com", null), "alice");

    assertThat(response.code()).isEqualTo(codes.encode(102));
  }

  @Test
  void emptyAliasMeansGenerated() {
    LinkResponse response =
        service.create(new CreateLinkRequest("https://example.com", ""), "alice");

    assertThat(response.customAlias()).isFalse();
  }

  @Test
  void createsAliasLink() {
    LinkResponse response =
        service.create(new CreateLinkRequest("https://example.com", "promo-2026"), "alice");

    assertThat(response.code()).isEqualTo("promo-2026");
    assertThat(response.customAlias()).isTrue();
  }

  @Test
  void rejectsInvalidUrlBeforeTouchingTheDatabase() {
    assertThatThrownBy(() -> service.create(new CreateLinkRequest("http://127.0.0.1", null), "a"))
        .isInstanceOf(InvalidUrlException.class);

    verify(repository, never()).save(any());
    verify(repository, never()).nextId();
  }

  @Test
  void rejectsInvalidAlias() {
    assertThatThrownBy(
            () -> service.create(new CreateLinkRequest("https://example.com", "api"), "a"))
        .isInstanceOf(InvalidAliasException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void aliasTakenWhenExistsCheckFinds() {
    when(repository.existsByCodeIgnoreCase("promo")).thenReturn(true);

    assertThatThrownBy(
            () -> service.create(new CreateLinkRequest("https://example.com", "promo"), "a"))
        .isInstanceOf(AliasTakenException.class);
  }

  @Test
  void aliasTakenWhenTheUniqueIndexWinsTheRace() {
    when(repository.save(any(Link.class)))
        .thenThrow(
            new DbActionExecutionException(null, new DuplicateKeyException("ux_link_alias_lower")));

    assertThatThrownBy(
            () -> service.create(new CreateLinkRequest("https://example.com", "promo"), "a"))
        .isInstanceOf(AliasTakenException.class);
  }

  @Test
  void otherDatabaseErrorsPropagateOnAliasCreate() {
    DbActionExecutionException boom =
        new DbActionExecutionException(null, new IllegalStateException());
    when(repository.save(any(Link.class))).thenThrow(boom);

    assertThatThrownBy(
            () -> service.create(new CreateLinkRequest("https://example.com", "promo"), "a"))
        .isSameAs(boom);
  }

  @Test
  void resolveReturnsTargetOfActiveLink() {
    when(repository.findByCode("abcd123"))
        .thenReturn(
            Optional.of(Link.newGenerated(1, "abcd123", "https://example.com/x", "a", CLOCK)));

    assertThat(service.resolve("abcd123")).isEqualTo(URI.create("https://example.com/x"));
  }

  @Test
  void resolveTreatsDeletedAndUnknownAsNotFound() {
    Link deleted =
        Link.newGenerated(1, "gone123", "https://example.com", "a", CLOCK).markDeleted(CLOCK);
    when(repository.findByCode("gone123")).thenReturn(Optional.of(deleted));

    assertThatThrownBy(() -> service.resolve("gone123")).isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(LinkNotFoundException.class);
  }

  @Test
  void getReturnsOwnLinkIncludingDeleted() {
    Link deleted =
        Link.newGenerated(1, "gone123", "https://example.com", "alice", CLOCK).markDeleted(CLOCK);
    when(repository.findByCode("gone123")).thenReturn(Optional.of(deleted));

    assertThat(service.get("gone123", "alice").status()).isEqualTo(LinkStatus.DELETED);
  }

  @Test
  void getHidesOtherOwnersLinks() {
    when(repository.findByCode("abcd123"))
        .thenReturn(
            Optional.of(Link.newGenerated(1, "abcd123", "https://example.com", "alice", CLOCK)));

    assertThatThrownBy(() -> service.get("abcd123", "mallory"))
        .isInstanceOf(LinkNotFoundException.class);
  }

  @Test
  void deleteSoftDeletesOwnActiveLink() {
    when(repository.findByCode("abcd123"))
        .thenReturn(
            Optional.of(Link.newGenerated(1, "abcd123", "https://example.com", "alice", CLOCK)));

    service.delete("abcd123", "alice");

    ArgumentCaptor<Link> saved = ArgumentCaptor.forClass(Link.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(LinkStatus.DELETED);
  }

  @Test
  void deleteIsIdempotentAndRespectsOwnership() {
    Link deleted =
        Link.newGenerated(1, "gone123", "https://example.com", "alice", CLOCK).markDeleted(CLOCK);
    when(repository.findByCode("gone123")).thenReturn(Optional.of(deleted));

    service.delete("gone123", "alice");
    verify(repository, never()).save(any());

    assertThatThrownBy(() -> service.delete("gone123", "mallory"))
        .isInstanceOf(LinkNotFoundException.class);
  }

  // ---- cache behaviour ----

  @Test
  void createPopulatesTheCache() {
    LinkResponse response =
        service.create(new CreateLinkRequest("https://example.com/c", null), "alice");

    assertThat(cache.entries)
        .containsEntry(response.code(), new CacheEntry.Found("https://example.com/c"));
  }

  @Test
  void resolveServesFromCacheWithoutTheDatabase() {
    cache.putFound("cached1", "https://example.com/cached");

    assertThat(service.resolve("cached1")).isEqualTo(URI.create("https://example.com/cached"));
    verify(repository, never()).findByCode("cached1");
  }

  @Test
  void resolveMissLoadsFromDbAndCachesIt() {
    when(repository.findByCode("abcd123"))
        .thenReturn(
            Optional.of(Link.newGenerated(1, "abcd123", "https://example.com/x", "a", CLOCK)));

    service.resolve("abcd123");
    service.resolve("abcd123");

    verify(repository, org.mockito.Mockito.times(1)).findByCode("abcd123");
    assertThat(cache.entries)
        .containsEntry("abcd123", new CacheEntry.Found("https://example.com/x"));
  }

  @Test
  void unknownCodesAreNegativelyCached() {
    assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> service.resolve("nope123")).isInstanceOf(LinkNotFoundException.class);

    verify(repository, org.mockito.Mockito.times(1)).findByCode("nope123");
    assertThat(cache.entries).containsEntry("nope123", CacheEntry.NOT_FOUND);
  }

  @Test
  void createdAliasOverwritesANegativeEntry() {
    assertThatThrownBy(() -> service.resolve("fresh-alias"))
        .isInstanceOf(LinkNotFoundException.class);

    service.create(new CreateLinkRequest("https://example.com/new", "fresh-alias"), "alice");

    assertThat(service.resolve("fresh-alias")).isEqualTo(URI.create("https://example.com/new"));
  }

  /**
   * Pins the seeded defect BF-1 (docs/known-issues.md): delete does not evict, so the deleted link
   * keeps redirecting from cache. The brownfield fix must change this test.
   */
  @Test
  void knownIssueBf1_deleteDoesNotEvictCache() {
    Link link = Link.newGenerated(1, "bf1code", "https://example.com/bf1", "alice", CLOCK);
    when(repository.findByCode("bf1code")).thenReturn(Optional.of(link));
    service.resolve("bf1code"); // warms the cache

    service.delete("bf1code", "alice");

    assertThat(service.resolve("bf1code")).isEqualTo(URI.create("https://example.com/bf1"));
    assertThat(cache.evicted).isEmpty();
  }

  /** Plain map cache: lets service tests observe cache effects without Caffeine or Redis. */
  static final class InMemoryLinkCache implements LinkCache {
    final Map<String, CacheEntry> entries = new HashMap<>();
    final List<String> evicted = new java.util.ArrayList<>();

    @Override
    public Optional<CacheEntry> get(String code) {
      return Optional.ofNullable(entries.get(code));
    }

    @Override
    public void putFound(String code, String originalUrl) {
      entries.put(code, new CacheEntry.Found(originalUrl));
    }

    @Override
    public void putNotFound(String code) {
      entries.put(code, CacheEntry.NOT_FOUND);
    }

    @Override
    public void evict(String code) {
      evicted.add(code);
      entries.remove(code);
    }
  }
}
