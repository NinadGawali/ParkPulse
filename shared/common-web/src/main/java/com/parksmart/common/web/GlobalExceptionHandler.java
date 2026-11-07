package com.parksmart.common.web;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 problem with a ParkSmart type URI and the correlation ID.
 * Unexpected exceptions become a generic 500 without internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final Map<Integer, String> DEFAULT_SLUGS =
      Map.ofEntries(
          Map.entry(400, "bad-request"),
          Map.entry(401, "unauthorized"),
          Map.entry(403, "forbidden"),
          Map.entry(404, "not-found"),
          Map.entry(405, "method-not-allowed"),
          Map.entry(406, "not-acceptable"),
          Map.entry(409, "conflict"),
          Map.entry(412, "precondition-failed"),
          Map.entry(413, "payload-too-large"),
          Map.entry(415, "unsupported-media-type"),
          Map.entry(422, "unprocessable"),
          Map.entry(428, "precondition-required"),
          Map.entry(429, "rate-limited"),
          Map.entry(500, "internal-error"),
          Map.entry(503, "service-unavailable"));

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
    return ResponseEntity.status(ex.status())
        .body(Problems.of(ex.status(), ex.slug(), ex.title(), ex.getMessage()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
    ProblemDetail problem =
        Problems.of(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Validation failed",
            "One or more parameters are invalid");
    problem.setProperty(
        "errors",
        ex.getConstraintViolations().stream()
            .map(v -> fieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
            .toList());
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError()
        .body(
            Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "An unexpected error occurred"));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        Problems.of(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Validation failed",
            "One or more fields are invalid");
    List<Map<String, String>> errors =
        ex.getBindingResult().getAllErrors().stream()
            .map(error ->
                error instanceof FieldError fieldError
                    ? fieldError(fieldError.getField(), fieldError.getDefaultMessage())
                    : fieldError(error.getObjectName(), error.getDefaultMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().headers(headers).body(problem);
  }

  /** Normalizes the problems Spring MVC produces itself (400, 405, 415, ...). */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      if (problem.getType() == null || URI.create("about:blank").equals(problem.getType())) {
        String slug = DEFAULT_SLUGS.getOrDefault(statusCode.value(), "error");
        problem.setType(URI.create(Problems.TYPE_BASE + slug));
      }
      String correlationId = CorrelationId.current();
      if (correlationId != null) {
        problem.setProperty("correlationId", correlationId);
      }
    }
    return response;
  }

  private static Map<String, String> fieldError(String field, String message) {
    return Map.of("field", field, "message", message == null ? "is invalid" : message);
  }

  private static String lastNode(String propertyPath) {
    int dot = propertyPath.lastIndexOf('.');
    return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
  }
}
