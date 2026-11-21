package com.acme.shortener.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline security headers. The service serves JSON and redirects, never HTML, so the CSP denies
 * everything; Swagger UI (a real page) is the exception. API responses carry per-owner data and are
 * {@code no-store}. HSTS only on HTTPS, where browsers honour it.
 *
 * <p>Referrer-Policy is the browser default, on purpose: on a redirect it governs what the
 * destination sees, and stripping the referrer entirely would surprise link owners.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

  static final String CSP = "default-src 'none'; frame-ancestors 'none'";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    if (!path.startsWith("/swagger-ui") && !path.startsWith("/v3/api-docs")) {
      response.setHeader("Content-Security-Policy", CSP);
    }
    if (path.startsWith("/api/")) {
      response.setHeader("Cache-Control", "no-store");
    }
    if (request.isSecure()) {
      response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }
    chain.doFilter(request, response);
  }
}
