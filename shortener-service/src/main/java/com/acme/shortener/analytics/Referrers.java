package com.acme.shortener.analytics;

import java.net.URI;
import java.util.Locale;

/** Reduces a Referer header to its host: the path and query can carry personal data. */
public final class Referrers {

  static final int MAX_HOST_LENGTH = 255;

  private Referrers() {}

  /** Lowercase host, or null if absent, unparsable or not http(s). */
  public static String host(String referer) {
    if (referer == null || referer.isBlank() || referer.length() > 4096) {
      return null;
    }
    try {
      URI uri = new URI(referer.strip());
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        return null;
      }
      String host = uri.getHost();
      if (host == null || host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
        return null;
      }
      return host.toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return null;
    }
  }
}
