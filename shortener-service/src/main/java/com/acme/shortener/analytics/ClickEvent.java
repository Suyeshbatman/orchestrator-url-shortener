package com.acme.shortener.analytics;

import java.time.Instant;

/**
 * One redirect, as recorded. Keyed by code, not link id: the cache only knows the code, and the
 * writer resolves ids for a whole batch in one query, so the redirect path never touches the DB.
 *
 * @param referrerHost host of the Referer header, or null
 * @param visitorHash see {@link com.acme.shortener.ratelimit.VisitorFingerprint}
 */
public record ClickEvent(
    String code, Instant occurredAt, String referrerHost, UaFamily uaFamily, String visitorHash) {}
