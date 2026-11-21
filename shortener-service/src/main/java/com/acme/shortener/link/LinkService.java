package com.acme.shortener.link;

import com.acme.shortener.cache.CacheEntry;
import com.acme.shortener.cache.LinkCache;
import com.acme.shortener.code.AliasTakenException;
import com.acme.shortener.code.AliasValidator;
import com.acme.shortener.code.CodeGenerator;
import com.acme.shortener.config.ShortenerProperties;
import com.acme.shortener.url.UrlValidator;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LinkService {

  /** Generated codes are collision-free among themselves; this only covers an alias clash. */
  private static final int MAX_GENERATE_ATTEMPTS = 3;

  private final LinkRepository links;
  private final CodeGenerator codeGenerator;
  private final AliasValidator aliasValidator;
  private final UrlValidator urlValidator;
  private final LinkCache cache;
  private final Clock clock;
  private final String baseUrl;

  public LinkService(
      LinkRepository links,
      CodeGenerator codeGenerator,
      AliasValidator aliasValidator,
      UrlValidator urlValidator,
      LinkCache cache,
      Clock clock,
      ShortenerProperties properties) {
    this.links = links;
    this.codeGenerator = codeGenerator;
    this.aliasValidator = aliasValidator;
    this.urlValidator = urlValidator;
    this.cache = cache;
    this.clock = clock;
    this.baseUrl = properties.baseUrl();
  }

  public LinkResponse create(CreateLinkRequest request, String owner) {
    URI url = urlValidator.validate(request.url());
    String alias = request.customAlias();
    Link saved =
        (alias == null || alias.isEmpty())
            ? createGenerated(url.toString(), owner)
            : createAlias(alias, url.toString(), owner);
    afterCommit(() -> cache.putFound(saved.code(), saved.originalUrl()));
    return LinkResponse.of(saved, baseUrl);
  }

  /** Redirect target for an active link. Public: no ownership check. Cache, then DB. */
  public URI resolve(String code) {
    CacheEntry entry = cache.get(code).orElseGet(() -> loadIntoCache(code));
    return switch (entry) {
      case CacheEntry.Found found -> URI.create(found.originalUrl());
      case CacheEntry.NotFound notFound -> throw new LinkNotFoundException(code);
    };
  }

  private CacheEntry loadIntoCache(String code) {
    return links
        .findByCode(code)
        .filter(Link::isActive)
        .<CacheEntry>map(link -> {
          cache.putFound(code, link.originalUrl());
          return new CacheEntry.Found(link.originalUrl());
        })
        .orElseGet(() -> {
          cache.putNotFound(code);
          return CacheEntry.NOT_FOUND;
        });
  }

  /** Metadata for the owner, including deleted links (status DELETED). */
  public LinkResponse get(String code, String owner) {
    return LinkResponse.of(findOwned(code, owner), baseUrl);
  }

  /** Soft delete. Idempotent: deleting a deleted link succeeds. */
  public void delete(String code, String owner) {
    Link link = findOwned(code, owner);
    if (link.isActive()) {
      links.save(link.markDeleted(clock));
      // KNOWN-ISSUE BF-1: cache not invalidated on delete. A deleted link keeps redirecting from
      // L1/L2 until its cache TTL expires (L1 5m, L2 24h). Seeded on purpose for the brownfield
      // scenario; see docs/known-issues.md. Do not fix outside that scenario.
    }
  }

  /** The link if {@code owner} created it, including deleted ones; otherwise 404. */
  public Link findOwned(String code, String owner) {
    // Another key's link answers 404 too, so existence isn't revealed.
    return links
        .findByCode(code)
        .filter(link -> Objects.equals(link.createdBy(), owner))
        .orElseThrow(() -> new LinkNotFoundException(code));
  }

  private Link createAlias(String alias, String url, String owner) {
    aliasValidator.validate(alias);
    try {
      return links.save(Link.newAlias(links.nextId(), alias, url, owner, clock));
    } catch (
        RuntimeException e) { // DataAccessException, or Spring Data's DbActionExecutionException
      if (isDuplicateKey(e)) {
        throw new AliasTakenException(alias); // lost a race; the unique index decided
      }
      throw e;
    }
  }

  private Link createGenerated(String url, String owner) {
    // Checked before insert rather than caught after: a failed INSERT aborts the surrounding
    // Postgres transaction (the idempotency transaction), so it can't be retried in place.
    for (int attempt = 1; attempt <= MAX_GENERATE_ATTEMPTS; attempt++) {
      CodeGenerator.GeneratedCode next = codeGenerator.next();
      if (links.findByCode(next.code()).isEmpty()) {
        return links.save(Link.newGenerated(next.id(), next.code(), url, owner, clock));
      }
      // A custom alias already uses this exact code; take the next id.
    }
    throw new IllegalStateException("could not allocate a free code");
  }

  /** Runs after the surrounding transaction commits, or now if there is none. */
  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private static boolean isDuplicateKey(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof DuplicateKeyException) {
        return true;
      }
    }
    return false;
  }
}
