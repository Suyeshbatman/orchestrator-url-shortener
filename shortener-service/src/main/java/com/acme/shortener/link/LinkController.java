package com.acme.shortener.link;

import com.acme.shortener.auth.ApiKeyAuthInterceptor;
import com.acme.shortener.auth.ApiKeyRegistry;
import com.acme.shortener.idempotency.IdempotencyService;
import com.acme.shortener.idempotency.IdempotencyService.StoredResponse;
import com.acme.shortener.ratelimit.RateLimitScope;
import com.acme.shortener.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Create, inspect and delete short links")
@SecurityRequirement(name = "apiKey")
class LinkController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final String REPLAYED = "Idempotent-Replayed";

  private final LinkService links;
  private final IdempotencyService idempotency;
  private final JsonMapper json;

  LinkController(LinkService links, IdempotencyService idempotency, JsonMapper json) {
    this.links = links;
    this.idempotency = idempotency;
    this.json = json;
  }

  @PostMapping
  @RateLimited(RateLimitScope.CREATE)
  @Operation(
      summary = "Create a short link",
      description =
          "Send Idempotency-Key to make retries safe: the same key and body replay the stored"
              + " response (header Idempotent-Replayed: true); the same key with a different body"
              + " returns 422. Keys are kept for 24h.")
  ResponseEntity<LinkResponse> create(
      @Valid @RequestBody CreateLinkRequest request,
      @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
      @Parameter(hidden = true) @RequestAttribute(ApiKeyAuthInterceptor.PRINCIPAL) String owner) {
    if (idempotencyKey == null) {
      return created(links.create(request, owner), false);
    }
    String requestHash = ApiKeyRegistry.sha256Hex(json.writeValueAsString(request));
    IdempotencyService.Result result =
        idempotency.execute(
            owner,
            idempotencyKey,
            requestHash,
            () -> new StoredResponse(201, json.writeValueAsString(links.create(request, owner))));
    return created(json.readValue(result.response().body(), LinkResponse.class), result.replayed());
  }

  @GetMapping("/{code}")
  @Operation(summary = "Get link metadata (deleted links are returned with status DELETED)")
  LinkResponse get(
      @PathVariable String code,
      @Parameter(hidden = true) @RequestAttribute(ApiKeyAuthInterceptor.PRINCIPAL) String owner) {
    return links.get(code, owner);
  }

  @DeleteMapping("/{code}")
  @Operation(summary = "Soft-delete a link (idempotent)")
  ResponseEntity<Void> delete(
      @PathVariable String code,
      @Parameter(hidden = true) @RequestAttribute(ApiKeyAuthInterceptor.PRINCIPAL) String owner) {
    links.delete(code, owner);
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<LinkResponse> created(LinkResponse body, boolean replayed) {
    return ResponseEntity.created(URI.create("/api/v1/links/" + body.code()))
        .header(REPLAYED, Boolean.toString(replayed))
        .body(body);
  }
}
