package com.parksmart.booking.api;

import com.parksmart.booking.domain.DomainException;
import com.parksmart.common.web.Problems;
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
          "booking-overlap", HttpStatus.CONFLICT,
          "illegal-booking-transition", HttpStatus.CONFLICT,
          "check-in-window", HttpStatus.CONFLICT,
          "too-many-pending-bookings", HttpStatus.TOO_MANY_REQUESTS,
          "invalid-booking-window", HttpStatus.UNPROCESSABLE_ENTITY);

  private static final Map<String, String> TITLES =
      Map.of(
          "booking-overlap", "Time already booked",
          "illegal-booking-transition", "Booking cannot change now",
          "check-in-window", "Check-in not open",
          "invalid-booking-window", "Invalid booking window",
          "listing-not-bookable", "Listing not bookable",
          "cannot-book-own-listing", "Cannot book own listing",
          "idempotency-key-reused", "Idempotency-Key reused",
          "too-many-pending-bookings", "Too many unpaid bookings");

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
