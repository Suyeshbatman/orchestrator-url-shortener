package com.acme.shortener.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards management endpoints ({@code /api/**}). The authenticated key name is exposed as a request
 * attribute; controllers read it with {@code @RequestAttribute(PRINCIPAL)}.
 */
public final class ApiKeyAuthInterceptor implements HandlerInterceptor {

  public static final String HEADER = "X-API-Key";
  public static final String PRINCIPAL = "shortener.apiKeyName";

  private final ApiKeyRegistry registry;

  public ApiKeyAuthInterceptor(ApiKeyRegistry registry) {
    this.registry = registry;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String name =
        registry.authenticate(request.getHeader(HEADER)).orElseThrow(UnauthorizedException::new);
    request.setAttribute(PRINCIPAL, name);
    return true;
  }
}
