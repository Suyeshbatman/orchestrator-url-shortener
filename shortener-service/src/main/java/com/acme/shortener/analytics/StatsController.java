package com.acme.shortener.analytics;

import com.acme.shortener.auth.ApiKeyAuthInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Links")
@SecurityRequirement(name = "apiKey")
class StatsController {

  private final StatsService stats;

  StatsController(StatsService stats) {
    this.stats = stats;
  }

  @GetMapping("/api/v1/links/{code}/stats")
  @Operation(
      summary = "Click stats for a link",
      description =
          "Days are UTC, both ends inclusive; the default is the last 30 days, the maximum 90."
              + " Clicks appear about a second after the redirect. uniqueVisitors is approximate"
              + " (HyperLogLog), and a visitor seen on two days counts twice.")
  LinkStats stats(
      @PathVariable String code,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @Parameter(hidden = true) @RequestAttribute(ApiKeyAuthInterceptor.PRINCIPAL) String owner) {
    return stats.stats(code, owner, from, to);
  }
}
