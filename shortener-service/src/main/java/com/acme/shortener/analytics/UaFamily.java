package com.acme.shortener.analytics;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Coarse browser family from the User-Agent. Order matters: Edge and most bots also claim Chrome
 * and Safari, and Chrome claims Safari.
 */
public enum UaFamily {
  BOT,
  EDGE,
  FIREFOX,
  CHROME,
  SAFARI,
  OTHER;

  private static final Pattern BOT_PATTERN =
      Pattern.compile("bot|crawl|spider|slurp|curl|wget|python-requests|httpclient|java/");
  private static final Pattern EDGE_PATTERN = Pattern.compile("edg(e|a|ios)?/");
  private static final Pattern FIREFOX_PATTERN = Pattern.compile("firefox/|fxios/");
  private static final Pattern CHROME_PATTERN = Pattern.compile("chrome/|crios/|chromium/");
  private static final Pattern SAFARI_PATTERN = Pattern.compile("safari/|applewebkit/");

  public static UaFamily of(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return OTHER;
    }
    String ua = userAgent.toLowerCase(Locale.ROOT);
    if (BOT_PATTERN.matcher(ua).find()) {
      return BOT;
    }
    if (EDGE_PATTERN.matcher(ua).find()) {
      return EDGE;
    }
    if (FIREFOX_PATTERN.matcher(ua).find()) {
      return FIREFOX;
    }
    if (CHROME_PATTERN.matcher(ua).find()) {
      return CHROME;
    }
    if (SAFARI_PATTERN.matcher(ua).find()) {
      return SAFARI;
    }
    return OTHER;
  }

  /** As shown in stats: "Chrome", "Bot", ... */
  public String label() {
    String name = name();
    return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
  }
}
