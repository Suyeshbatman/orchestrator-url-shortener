package com.acme.shortener.link;

import com.acme.shortener.config.ShortenerProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public redirect. 302 by default so every click reaches the service and deletes take effect
 * (ADR-0002). The path pattern keeps it from shadowing /api, /actuator or /swagger-ui.
 */
@RestController
@Tag(name = "Redirect")
class RedirectController {

  private final LinkService links;
  private final int redirectStatus;

  RedirectController(LinkService links, ShortenerProperties properties) {
    this.links = links;
    this.redirectStatus = properties.redirect().status();
  }

  @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
  @Operation(summary = "Redirect to the original URL")
  ResponseEntity<Void> redirect(@PathVariable String code) {
    return ResponseEntity.status(redirectStatus)
        .location(links.resolve(code))
        .cacheControl(CacheControl.maxAge(Duration.ZERO).cachePrivate())
        .build();
  }
}
