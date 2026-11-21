package com.acme.orchestrator.app.api;

import org.springframework.http.HttpStatus;

/** An error the API reports as RFC 9457 problem details with a stable type. */
public final class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final ProblemTypes.Type type;

  public ApiException(HttpStatus status, ProblemTypes.Type type, String detail) {
    super(detail);
    this.status = status;
    this.type = type;
  }

  public HttpStatus status() {
    return status;
  }

  public ProblemTypes.Type type() {
    return type;
  }

  public static ApiException notFound(ProblemTypes.Type type, String detail) {
    return new ApiException(HttpStatus.NOT_FOUND, type, detail);
  }

  public static ApiException conflict(ProblemTypes.Type type, String detail) {
    return new ApiException(HttpStatus.CONFLICT, type, detail);
  }

  public static ApiException badRequest(String detail) {
    return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, detail);
  }
}
