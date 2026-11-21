package com.acme.orchestrator.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Maps errors to RFC 9457 Problem Details with the stable types of {@link ProblemTypes}. */
@RestControllerAdvice
class ApiProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiProblemHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest request) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
    if (e.status() == HttpStatus.UNAUTHORIZED) {
      response.header(
          HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + ApiKeyInterceptor.HEADER + "\"");
    }
    return response.body(problem(e.status(), e.type(), e.getMessage(), request));
  }

  @ExceptionHandler(RuntimeException.class)
  ProblemDetail unexpected(RuntimeException e, HttpServletRequest request) {
    LOG.error("unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ProblemTypes.INTERNAL_ERROR,
        "something went wrong; the details are in the server log",
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
            ((ServletWebRequest) request).getRequest());
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(f ->
                Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
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
                "the request body is not valid JSON for this endpoint",
                ((ServletWebRequest) request).getRequest()));
  }

  static ProblemDetail problem(
      HttpStatus status, ProblemTypes.Type type, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type.uri());
    problem.setTitle(type.title());
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    return problem;
  }
}
