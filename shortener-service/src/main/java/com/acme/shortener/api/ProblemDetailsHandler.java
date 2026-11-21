package com.acme.shortener.api;

import com.acme.shortener.analytics.InvalidStatsRangeException;
import com.acme.shortener.auth.ApiKeyAuthInterceptor;
import com.acme.shortener.auth.UnauthorizedException;
import com.acme.shortener.code.AliasTakenException;
import com.acme.shortener.code.InvalidAliasException;
import com.acme.shortener.idempotency.IdempotencyKeyReusedException;
import com.acme.shortener.idempotency.InvalidIdempotencyKeyException;
import com.acme.shortener.link.LinkNotFoundException;
import com.acme.shortener.ratelimit.RateLimitHeaders;
import com.acme.shortener.ratelimit.RateLimitedException;
import com.acme.shortener.url.InvalidUrlException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 Problem Details. Each error has a stable {@code type} URI that
 * clients (and agent-written tests) can assert on. See {@link ProblemTypes}.
 */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

  @ExceptionHandler(InvalidUrlException.class)
  ProblemDetail invalidUrl(InvalidUrlException e, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_URL, e.getMessage(), request);
    problem.setProperty("reason", e.reason().name());
    return problem;
  }

  @ExceptionHandler(InvalidAliasException.class)
  ProblemDetail invalidAlias(InvalidAliasException e, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_ALIAS, e.getMessage(), request);
  }

  @ExceptionHandler(InvalidIdempotencyKeyException.class)
  ProblemDetail invalidIdempotencyKey(
      InvalidIdempotencyKeyException e, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_IDEMPOTENCY_KEY, e.getMessage(), request);
  }

  @ExceptionHandler(UnauthorizedException.class)
  ResponseEntity<ProblemDetail> unauthorized(UnauthorizedException e, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(
            HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + ApiKeyAuthInterceptor.HEADER + "\"")
        .body(problem(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, e.getMessage(), request));
  }

  @ExceptionHandler(LinkNotFoundException.class)
  ProblemDetail notFound(LinkNotFoundException e, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, ProblemTypes.LINK_NOT_FOUND, e.getMessage(), request);
  }

  @ExceptionHandler(AliasTakenException.class)
  ProblemDetail aliasTaken(AliasTakenException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, ProblemTypes.ALIAS_TAKEN, e.getMessage(), request);
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_CONTENT,
        ProblemTypes.IDEMPOTENCY_KEY_REUSED,
        e.getMessage(),
        request);
  }

  @ExceptionHandler(InvalidStatsRangeException.class)
  ProblemDetail invalidStatsRange(InvalidStatsRangeException e, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_STATS_RANGE, e.getMessage(), request);
  }

  @ExceptionHandler(RateLimitedException.class)
  ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .headers(RateLimitHeaders.of(e.decision()))
        .body(
            problem(
                HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, e.getMessage(), request));
  }

  /** Last resort. Logged with the stack trace; the client never sees internals. */
  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ProblemTypes.INTERNAL_ERROR,
        "an unexpected error occurred",
        request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            ProblemTypes.VALIDATION_FAILED,
            "request has invalid fields",
            servletRequest(request));
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(f ->
                Map.of(
                    "field",
                    f.getField(),
                    "message",
                    f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest()
        .body(
            problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "request body is missing or is not valid JSON",
                servletRequest(request)));
  }

  /** A query or path parameter of the wrong type, e.g. {@code ?from=yesterday}. */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = e.getPropertyName() == null ? "parameter" : "'" + e.getPropertyName() + "'";
    return ResponseEntity.badRequest()
        .body(
            problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.VALIDATION_FAILED,
                name + " has an invalid value",
                servletRequest(request)));
  }

  private static ProblemDetail problem(
      HttpStatus status, ProblemTypes.Type type, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type.uri());
    problem.setTitle(type.title());
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }

  private static HttpServletRequest servletRequest(WebRequest request) {
    return ((ServletWebRequest) request).getRequest();
  }
}
