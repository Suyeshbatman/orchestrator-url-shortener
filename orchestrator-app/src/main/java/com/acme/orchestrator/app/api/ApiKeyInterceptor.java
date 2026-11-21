package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.api.ApiKeys.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards {@code /api/**}: every call needs a valid {@code X-API-Key} (401 otherwise); endpoints
 * marked {@link RequiresRole} also need that role (403). The caller is exposed as a request
 * attribute for controllers.
 */
public final class ApiKeyInterceptor implements HandlerInterceptor {

  public static final String HEADER = "X-API-Key";
  public static final String PRINCIPAL = "orchestrator.principal";

  private final ApiKeys keys;

  public ApiKeyInterceptor(ApiKeys keys) {
    this.keys = keys;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Principal principal =
        keys.authenticate(request.getHeader(HEADER))
            .orElseThrow(() ->
                new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    ProblemTypes.UNAUTHORIZED,
                    "a valid " + HEADER + " header is required"));
    if (handler instanceof HandlerMethod method) {
      RequiresRole required = method.getMethodAnnotation(RequiresRole.class);
      if (required != null && !principal.has(required.value())) {
        throw new ApiException(
            HttpStatus.FORBIDDEN,
            ProblemTypes.FORBIDDEN,
            principal.name() + " does not have the " + required.value() + " role");
      }
    }
    request.setAttribute(PRINCIPAL, principal);
    return true;
  }
}
