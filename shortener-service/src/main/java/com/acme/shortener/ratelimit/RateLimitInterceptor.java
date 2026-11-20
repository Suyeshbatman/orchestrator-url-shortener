package com.acme.shortener.ratelimit;

import com.acme.shortener.auth.ApiKeyAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RateLimited} on controller methods. Must run after {@link ApiKeyAuthInterceptor}
 * so create can be keyed by the API key name. Allowed requests get the RateLimit-* headers here; a
 * rejection throws, and the 429 handler writes the headers.
 *
 * <p>The client IP is {@code getRemoteAddr()}. Behind a proxy, set {@code
 * server.forward-headers-strategy} rather than trusting X-Forwarded-For here (it is spoofable).
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimitService limits;
  private final VisitorFingerprint fingerprints;

  public RateLimitInterceptor(RateLimitService limits, VisitorFingerprint fingerprints) {
    this.limits = limits;
    this.fingerprints = fingerprints;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
    if (annotation == null) {
      return true;
    }
    RateLimitScope scope = annotation.value();
    String subject =
        switch (scope) {
          case CREATE ->
              Objects.requireNonNull(
                  (String) request.getAttribute(ApiKeyAuthInterceptor.PRINCIPAL),
                  "create is rate limited by API key; authentication must run first");
          case REDIRECT -> visitor(request);
        };
    Decision decision = limits.check(scope, subject);
    if (!decision.allowed()) {
      throw new RateLimitedException(decision);
    }
    HttpHeaders headers = RateLimitHeaders.of(decision);
    headers.forEach((name, values) -> response.setHeader(name, values.getFirst()));
    return true;
  }

  private String visitor(HttpServletRequest request) {
    String hash =
        fingerprints.of(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    request.setAttribute(VisitorFingerprint.ATTRIBUTE, hash);
    return hash;
  }
}
