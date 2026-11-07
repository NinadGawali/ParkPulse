package com.parksmart.profile.api;

import com.parksmart.common.web.Problems;
import com.parksmart.profile.domain.DomainException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain rule violations to RFC 7807 problems (type slug = domain code). {@code invalid-*}
 * codes are 400; unknown codes are 422.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionHandler {

  private static final Map<String, HttpStatus> STATUS_BY_CODE =
      Map.ofEntries(
          Map.entry("plate-registered", HttpStatus.CONFLICT),
          Map.entry("payout-registered", HttpStatus.CONFLICT),
          Map.entry("concurrent-modification", HttpStatus.CONFLICT),
          Map.entry("upload-confirmed", HttpStatus.CONFLICT),
          Map.entry("vehicle-removed", HttpStatus.NOT_FOUND));

  private static final Map<String, String> TITLES =
      Map.ofEntries(
          Map.entry("plate-registered", "Plate already registered"),
          Map.entry("payout-registered", "Payout account already registered"),
          Map.entry("concurrent-modification", "Concurrent modification"),
          Map.entry("upload-confirmed", "Upload already confirmed"),
          Map.entry("vehicle-removed", "Vehicle removed"),
          Map.entry("vehicle-limit", "Too many vehicles"),
          Map.entry("payout-limit", "Too many payout accounts"),
          Map.entry("too-many-pending-uploads", "Too many pending uploads"),
          Map.entry("upload-expired", "Upload expired"),
          Map.entry("upload-missing", "Upload missing"),
          Map.entry("upload-mismatch", "Upload does not match"),
          Map.entry("channel-required", "Channel cannot be disabled"),
          Map.entry("processor-not-allowed", "Processor not allowed"));

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handle(DomainException ex) {
    HttpStatus status =
        ex.code().startsWith("invalid-")
            ? HttpStatus.BAD_REQUEST
            : STATUS_BY_CODE.getOrDefault(ex.code(), HttpStatus.UNPROCESSABLE_ENTITY);
    String title =
        ex.code().startsWith("invalid-")
            ? "Invalid value"
            : TITLES.getOrDefault(ex.code(), "Request rejected");
    return ResponseEntity.status(status)
        .body(Problems.of(status, ex.code(), title, ex.getMessage()));
  }
}
