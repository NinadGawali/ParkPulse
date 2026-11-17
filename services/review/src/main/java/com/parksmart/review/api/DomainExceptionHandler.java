package com.parksmart.review.api;

import com.parksmart.common.web.Problems;
import com.parksmart.review.domain.DomainException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rule violations to RFC 7807 problems (type slug = domain code). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionHandler {

  private static final Map<String, String> TITLES =
      Map.of(
          "review-exists", "Already reviewed",
          "review-window-closed", "Review window closed",
          "invalid-rating", "Invalid rating");

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handle(DomainException ex) {
    HttpStatus status =
        switch (ex.code()) {
          case "review-exists" -> HttpStatus.CONFLICT;
          case "review-window-closed" -> HttpStatus.UNPROCESSABLE_ENTITY;
          default ->
              ex.code().startsWith("invalid-")
                  ? HttpStatus.BAD_REQUEST
                  : HttpStatus.UNPROCESSABLE_ENTITY;
        };
    return ResponseEntity.status(status)
        .body(
            Problems.of(
                status,
                ex.code(),
                TITLES.getOrDefault(ex.code(), "Request rejected"),
                ex.getMessage()));
  }
}
