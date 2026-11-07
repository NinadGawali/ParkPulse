package com.parksmart.listing.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.security.Role;
import com.parksmart.common.web.CursorPage;
import com.parksmart.listing.api.dto.Requests;
import com.parksmart.listing.api.dto.Responses.ListingResponse;
import com.parksmart.listing.api.dto.Responses.ListingSummary;
import com.parksmart.listing.application.ListingService;
import com.parksmart.listing.application.ListingService.ListingView;
import com.parksmart.listing.application.ListingService.Versioned;
import com.parksmart.listing.domain.Enums.ListingStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ownership is checked in ListingService (403 for anyone but the host). */
@RestController
@RequestMapping("/api/v1")
class ListingController {

  private final ListingService listings;

  ListingController(ListingService listings) {
    this.listings = listings;
  }

  @PostMapping("/listings")
  ResponseEntity<ListingResponse> create(@Valid @RequestBody Requests.CreateListing body) {
    UUID host = CurrentAccount.requireRole(Role.HOST).accountId();
    Versioned created = listings.create(host, body.toTerms());
    return ResponseEntity.created(URI.create("/api/v1/listings/" + created.listing().id()))
        .eTag(ETags.of(created.version()))
        .body(ListingResponse.forOwner(created.listing()));
  }

  @GetMapping("/listings/{listingId}")
  ResponseEntity<ListingResponse> get(@PathVariable UUID listingId) {
    ListingView view = listings.view(CurrentAccount.find(), listingId);
    return ResponseEntity.ok().eTag(ETags.of(view.version())).body(ListingResponse.of(view));
  }

  @PatchMapping("/listings/{listingId}")
  ResponseEntity<ListingResponse> update(
      @PathVariable UUID listingId,
      @RequestHeader("If-Match") String ifMatch,
      @Valid @RequestBody Requests.UpdateListing body) {
    Versioned updated =
        listings.update(callerId(), listingId, ETags.parse(ifMatch), body.toTerms());
    return owner(updated);
  }

  @DeleteMapping("/listings/{listingId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void archive(@PathVariable UUID listingId) {
    listings.archive(callerId(), listingId);
  }

  @GetMapping("/hosts/me/listings")
  CursorPage<ListingSummary> mine(
      @RequestParam(required = false) ListingStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return listings.mine(callerId(), status, cursor, limit).map(ListingSummary::of);
  }

  @PostMapping("/listings/{listingId}/submit")
  ResponseEntity<ListingResponse> submit(@PathVariable UUID listingId) {
    return owner(listings.submit(CurrentAccount.require(), listingId));
  }

  @PostMapping("/listings/{listingId}/pause")
  ResponseEntity<ListingResponse> pause(@PathVariable UUID listingId) {
    return owner(listings.pause(callerId(), listingId));
  }

  @PostMapping("/listings/{listingId}/resume")
  ResponseEntity<ListingResponse> resume(@PathVariable UUID listingId) {
    return owner(listings.resume(callerId(), listingId));
  }

  static ResponseEntity<ListingResponse> owner(Versioned v) {
    return ResponseEntity.ok()
        .eTag(ETags.of(v.version()))
        .body(ListingResponse.forOwner(v.listing()));
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }
}
