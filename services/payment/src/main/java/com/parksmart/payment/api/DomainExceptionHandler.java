package com.parksmart.payment.api;

import com.parksmart.common.web.Problems;
import com.parksmart.payment.domain.DomainException;
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

  private static final Map<String, HttpStatus> STATUS_BY_CODE =
      Map.of(
          "payment-window-closed", HttpStatus.CONFLICT,
          "illegal-payment-transition", HttpStatus.CONFLICT);

  private static final Map<String, String> TITLES =
      Map.of(
          "payment-window-closed", "Payment window closed",
          "illegal-payment-transition", "Payment cannot change now",
          "refund-exceeds-captured", "Refund too large",
          "refund-failed", "Refund failed",
          "idempotency-key-reused", "Idempotency-Key reused");

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handle(DomainException ex) {
    HttpStatus status =
        STATUS_BY_CODE.getOrDefault(
            ex.code(),
            ex.code().startsWith("invalid-")
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.UNPROCESSABLE_ENTITY);
    return ResponseEntity.status(status)
        .body(
            Problems.of(
                status,
                ex.code(),
                TITLES.getOrDefault(ex.code(), "Request rejected"),
                ex.getMessage()));
  }
}
