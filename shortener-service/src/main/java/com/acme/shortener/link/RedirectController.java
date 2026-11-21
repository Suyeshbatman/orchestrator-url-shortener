package com.acme.shortener.link;

import com.acme.shortener.analytics.ClickEvent;
import com.acme.shortener.analytics.ClickEventPublisher;
import com.acme.shortener.analytics.Referrers;
import com.acme.shortener.analytics.UaFamily;
import com.acme.shortener.config.ShortenerProperties;
import com.acme.shortener.ratelimit.RateLimitScope;
import com.acme.shortener.ratelimit.RateLimited;
import com.acme.shortener.ratelimit.VisitorFingerprint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public redirect. 302 by default so every click reaches the service and deletes take effect
 * (ADR-0002). The path pattern keeps it from shadowing /api, /actuator or /swagger-ui.
 *
 * <p>Each successful redirect publishes a click without waiting (ADR-0004); a full queue drops it.
 */
@RestController
@Tag(name = "Redirect")
class RedirectController {

  private final LinkService links;
  private final int redirectStatus;
  private final ClickEventPublisher clicks;
  private final VisitorFingerprint fingerprints;
  private final Clock clock;

  RedirectController(
      LinkService links,
      ShortenerProperties properties,
      ClickEventPublisher clicks,
      VisitorFingerprint fingerprints,
      Clock clock) {
    this.links = links;
    this.redirectStatus = properties.redirect().status();
    this.clicks = clicks;
    this.fingerprints = fingerprints;
    this.clock = clock;
  }

  @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
  @RateLimited(RateLimitScope.REDIRECT)
  @Operation(summary = "Redirect to the original URL")
  ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
    URI target = links.resolve(code);
    clicks.publish(click(code, request));
    return ResponseEntity.status(redirectStatus)
        .location(target)
        .cacheControl(CacheControl.maxAge(Duration.ZERO).cachePrivate())
        .build();
  }

  private ClickEvent click(String code, HttpServletRequest request) {
    String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
    // The rate limiter has usually computed it already; not if rate limiting is disabled.
    Object visitor = request.getAttribute(VisitorFingerprint.ATTRIBUTE);
    String visitorHash =
        visitor instanceof String s ? s : fingerprints.of(request.getRemoteAddr(), userAgent);
    return new ClickEvent(
        code,
        clock.instant(),
        Referrers.host(request.getHeader(HttpHeaders.REFERER)),
        UaFamily.of(userAgent),
        visitorHash);
  }
}
