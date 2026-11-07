package com.parksmart.listing.api;

import com.parksmart.common.web.Problems;
import com.parksmart.listing.domain.DomainException;
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
 * codes are 400; codes not listed are 422.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DomainExceptionHandler {

  private static final Map<String, HttpStatus> STATUS_BY_CODE =
      Map.ofEntries(
          Map.entry("listing-not-editable", HttpStatus.CONFLICT),
          Map.entry("illegal-transition", HttpStatus.CONFLICT),
          Map.entry("listing-archived", HttpStatus.CONFLICT),
          Map.entry("last-photo", HttpStatus.CONFLICT),
          Map.entry("photo-already-confirmed", HttpStatus.CONFLICT),
          Map.entry("photo-not-found", HttpStatus.NOT_FOUND),
          Map.entry("blackout-not-found", HttpStatus.NOT_FOUND));

  private static final Map<String, String> TITLES =
      Map.ofEntries(
          Map.entry("listing-not-editable", "Listing cannot be edited now"),
          Map.entry("illegal-transition", "Illegal status change"),
          Map.entry("listing-archived", "Listing archived"),
          Map.entry("last-photo", "Last photo"),
          Map.entry("photo-already-confirmed", "Photo already confirmed"),
          Map.entry("photo-not-found", "Resource not found"),
          Map.entry("blackout-not-found", "Resource not found"),
          Map.entry("listing-not-submittable", "Listing not ready for review"),
          Map.entry("photo-limit", "Too many photos"),
          Map.entry("blackout-limit", "Too many blackouts"),
          Map.entry("host-suspended", "Host suspended"),
          Map.entry("upload-expired", "Upload expired"),
          Map.entry("upload-missing", "Upload missing"),
          Map.entry("upload-mismatch", "Upload does not match"));

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> handle(DomainException ex) {
    boolean invalid = ex.code().startsWith("invalid-");
    HttpStatus status =
        invalid
            ? HttpStatus.BAD_REQUEST
            : STATUS_BY_CODE.getOrDefault(ex.code(), HttpStatus.UNPROCESSABLE_ENTITY);
    String title = invalid ? "Invalid value" : TITLES.getOrDefault(ex.code(), "Request rejected");
    return ResponseEntity.status(status)
        .body(Problems.of(status, ex.code(), title, ex.getMessage()));
  }
}
