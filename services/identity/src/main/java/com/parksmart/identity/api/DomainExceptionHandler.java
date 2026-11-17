package com.parksmart.identity.api;

import com.parksmart.common.web.Problems;
import com.parksmart.identity.domain.DomainException;
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
          "email-taken", HttpStatus.CONFLICT,
          "phone-in-use", HttpStatus.CONFLICT,
          "phone-already-verified", HttpStatus.CONFLICT,
          "email-already-verified", HttpStatus.CONFLICT,
          "concurrent-modification", HttpStatus.CONFLICT,
          "weak-password", HttpStatus.BAD_REQUEST,
          "account-not-active", HttpStatus.UNPROCESSABLE_ENTITY,
          "invalid-code", HttpStatus.UNPROCESSABLE_ENTITY,
          "invalid-token", HttpStatus.UNPROCESSABLE_ENTITY);

  private static final Map<String, String> TITLES =
      Map.of(
          "email-taken", "Email already registered",
          "phone-in-use", "Phone already in use",
          "phone-already-verified", "Phone already verified",
          "email-already-verified", "Email already verified",
          "concurrent-modification", "Concurrent modification",
          "weak-password", "Password too weak",
          "account-not-active", "Account not active",
          "invalid-code", "Invalid code",
          "invalid-token", "Invalid token");

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handle(DomainException ex) {
    HttpStatus status = STATUS_BY_CODE.getOrDefault(ex.code(), HttpStatus.UNPROCESSABLE_ENTITY);
    String title = TITLES.getOrDefault(ex.code(), "Request rejected");
    return ResponseEntity.status(status)
        .body(Problems.of(status, ex.code(), title, ex.getMessage()));
  }
}
