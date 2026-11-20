package com.acme.shortener.link;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/links}. URL and alias rules are enforced by their validators. */
public record CreateLinkRequest(
    @Schema(example = "https://example.com/some/very/long/path") @NotBlank String url,
    @Schema(
            example = "promo-2026",
            description = "Optional. 4-32 chars of letters, digits, '_' or '-'.",
            nullable = true)
        String customAlias) {}
