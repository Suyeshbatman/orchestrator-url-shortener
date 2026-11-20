package com.acme.shortener.link;

import java.time.Instant;

/** Link representation returned by the management API. */
public record LinkResponse(
    String code,
    String shortUrl,
    String originalUrl,
    boolean customAlias,
    LinkStatus status,
    Instant createdAt,
    Instant updatedAt) {

  static LinkResponse of(Link link, String baseUrl) {
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return new LinkResponse(
        link.code(),
        base + "/" + link.code(),
        link.originalUrl(),
        link.customAlias(),
        link.status(),
        link.createdAt(),
        link.updatedAt());
  }
}
