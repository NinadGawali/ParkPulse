package com.parksmart.listing.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.listing.api.dto.Requests;
import com.parksmart.listing.api.dto.Responses;
import com.parksmart.listing.api.dto.Responses.ListingResponse;
import com.parksmart.listing.api.dto.Responses.Photo;
import com.parksmart.listing.api.dto.Responses.PhotoUploadResponse;
import com.parksmart.listing.application.AvailabilityService;
import com.parksmart.listing.application.PhotoService;
import com.parksmart.listing.domain.AvailabilityRule;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Photos, weekly rules and blackouts. Owner only; checked in the services (403). */
@RestController
@RequestMapping("/api/v1/listings/{listingId}")
class PhotoAndAvailabilityController {

  private final PhotoService photos;
  private final AvailabilityService availability;

  PhotoAndAvailabilityController(PhotoService photos, AvailabilityService availability) {
    this.photos = photos;
    this.availability = availability;
  }

  @PostMapping("/photos/upload-urls")
  @ResponseStatus(HttpStatus.CREATED)
  PhotoUploadResponse startUpload(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.PhotoUpload body) {
    return PhotoUploadResponse.of(
        photos.startUpload(callerId(), listingId, body.contentType(), body.contentLength()));
  }

  @PostMapping("/photos/{photoId}/confirm")
  Photo confirm(@PathVariable UUID listingId, @PathVariable UUID photoId) {
    return Photo.of(photos.confirm(callerId(), listingId, photoId));
  }

  @DeleteMapping("/photos/{photoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deletePhoto(@PathVariable UUID listingId, @PathVariable UUID photoId) {
    photos.delete(callerId(), listingId, photoId);
  }

  @PutMapping("/photos/order")
  ListingResponse reorder(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.PhotoOrder body) {
    return ListingResponse.forOwner(photos.reorder(callerId(), listingId, body.photoIds()));
  }

  @PutMapping("/availability")
  ListingResponse replaceRules(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.Rules body) {
    return ListingResponse.forOwner(
        availability.replaceRules(
            callerId(),
            listingId,
            body.rules().stream()
                .map(r -> AvailabilityRule.parse(r.dayOfWeek(), r.startTime(), r.endTime()))
                .toList()));
  }

  @PostMapping("/blackouts")
  @ResponseStatus(HttpStatus.CREATED)
  Responses.Blackout addBlackout(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.Blackout body) {
    return Responses.Blackout.of(
        availability.addBlackout(callerId(), listingId, body.start(), body.end(), body.reason()),
        true);
  }

  @DeleteMapping("/blackouts/{blackoutId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeBlackout(@PathVariable UUID listingId, @PathVariable UUID blackoutId) {
    availability.removeBlackout(callerId(), listingId, blackoutId);
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }
}
