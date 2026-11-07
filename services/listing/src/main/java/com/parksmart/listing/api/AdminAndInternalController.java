package com.parksmart.listing.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.security.Role;
import com.parksmart.common.web.CursorPage;
import com.parksmart.listing.api.dto.Requests;
import com.parksmart.listing.api.dto.Responses.BookingQuote;
import com.parksmart.listing.api.dto.Responses.ListingResponse;
import com.parksmart.listing.api.dto.Responses.ListingSummary;
import com.parksmart.listing.application.AvailabilityService;
import com.parksmart.listing.application.ListingService;
import com.parksmart.listing.domain.Enums.ListingStatus;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moderation (ADMIN only) and the internal booking quote (DRIVER token forwarded by Booking; the
 * gateway never routes {@code /internal/**}).
 */
@RestController
class AdminAndInternalController {

  private final ListingService listings;
  private final AvailabilityService availability;

  AdminAndInternalController(ListingService listings, AvailabilityService availability) {
    this.listings = listings;
    this.availability = availability;
  }

  @GetMapping("/admin/v1/listings")
  CursorPage<ListingSummary> queue(
      @RequestParam ListingStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CurrentAccount.requireRole(Role.ADMIN);
    return listings.byStatus(status, cursor, limit).map(ListingSummary::of);
  }

  @PostMapping("/admin/v1/listings/{listingId}/approve")
  ResponseEntity<ListingResponse> approve(
      @PathVariable UUID listingId,
      @Valid @RequestBody(required = false) Requests.Moderation body) {
    UUID admin = CurrentAccount.requireRole(Role.ADMIN).accountId();
    return ListingController.owner(
        listings.approve(admin, listingId, body == null ? null : body.reason()));
  }

  @PostMapping("/admin/v1/listings/{listingId}/reject")
  ResponseEntity<ListingResponse> reject(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.Rejection body) {
    UUID admin = CurrentAccount.requireRole(Role.ADMIN).accountId();
    return ListingController.owner(listings.reject(admin, listingId, body.reason()));
  }

  @GetMapping("/internal/v1/listings/{listingId}/booking-quote")
  BookingQuote quote(
      @PathVariable UUID listingId, @RequestParam Instant start, @RequestParam Instant end) {
    CurrentAccount.requireRole(Role.DRIVER);
    return BookingQuote.of(availability.quote(listingId, start, end));
  }
}
