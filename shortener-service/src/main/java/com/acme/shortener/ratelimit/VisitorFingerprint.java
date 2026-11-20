package com.acme.shortener.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Identifies a visitor without storing their IP: HMAC-SHA256(secret, day | ip | user agent). That
 * is SHA-256 with a salt that rotates daily (UTC), derived from a server secret so every instance
 * computes the same value for the same visitor on the same day. Yesterday's hashes can't be linked
 * to today's.
 */
public final class VisitorFingerprint {

  /** Request attribute holding the fingerprint once computed (reused by analytics). */
  public static final String ATTRIBUTE = "shortener.visitorHash";

  private final Clock clock;
  private final ThreadLocal<Mac> mac;

  public VisitorFingerprint(String secret, Clock clock) {
    this.clock = clock;
    SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.mac =
        ThreadLocal.withInitial(() -> {
          try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(key);
            return m;
          } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** 64 lowercase hex characters. A missing user agent counts as empty. */
  public String of(String ip, String userAgent) {
    LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    // Header values can't contain newlines, so "\n" separates the fields unambiguously.
    String message = "visitor\n" + day + "\n" + ip + "\n" + (userAgent == null ? "" : userAgent);
    return HexFormat.of().formatHex(mac.get().doFinal(message.getBytes(StandardCharsets.UTF_8)));
  }
}
