package com.parksmart.adminbff.api;

import com.parksmart.adminbff.api.dto.Requests;
import com.parksmart.adminbff.api.dto.Responses.BookingCaseDto;
import com.parksmart.adminbff.api.dto.Responses.ListingModerationOutcome;
import com.parksmart.adminbff.api.dto.Responses.ModerationQueueItemDto;
import com.parksmart.adminbff.api.dto.Responses.PaymentFacts;
import com.parksmart.adminbff.api.dto.Responses.ReviewModerationOutcome;
import com.parksmart.adminbff.application.BookingCaseService;
import com.parksmart.adminbff.application.ModerationService;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CursorPage;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/admin-bff.yaml. ADMIN is checked by the filter chain and the services. */
@RestController
class AdminController {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");
  private static final int MAX_CURSOR = 512;
  private static final int MAX_LIMIT = 50;

  private final ModerationService moderation;
  private final BookingCaseService cases;

  AdminController(ModerationService moderation, BookingCaseService cases) {
    this.moderation = moderation;
    this.cases = cases;
  }

  @GetMapping("/admin/v1/moderation/listings")
  CursorPage<ModerationQueueItemDto> queue(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw invalid("limit must be between 1 and " + MAX_LIMIT);
    }
    if (cursor != null && (cursor.isEmpty() || cursor.length() > MAX_CURSOR)) {
      throw invalid("cursor is invalid");
    }
    return moderation.queue(cursor, limit).map(ModerationQueueItemDto::of);
  }

  @PostMapping("/admin/v1/moderation/listings/{listingId}/approve")
  ListingModerationOutcome approve(
      @PathVariable UUID listingId,
      @Valid @RequestBody(required = false) Requests.ModerationNote body) {
    return ListingModerationOutcome.of(
        moderation.approve(listingId, body == null ? null : body.reason()));
  }

  @PostMapping("/admin/v1/moderation/listings/{listingId}/reject")
  ListingModerationOutcome reject(
      @PathVariable UUID listingId, @Valid @RequestBody Requests.ModerationReason body) {
    return ListingModerationOutcome.of(moderation.reject(listingId, body.reason()));
  }

  @PostMapping("/admin/v1/moderation/reviews/{reviewId}/hide")
  ReviewModerationOutcome hideReview(
      @PathVariable UUID reviewId, @Valid @RequestBody Requests.HideReason body) {
    return ReviewModerationOutcome.of(moderation.hideReview(reviewId, body.reason()));
  }

  @GetMapping("/admin/v1/bookings/{bookingId}")
  BookingCaseDto bookingCase(@PathVariable UUID bookingId) {
    return BookingCaseDto.of(cases.find(bookingId));
  }

  @PostMapping("/admin/v1/bookings/{bookingId}/refunds")
  PaymentFacts refund(
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody Requests.Refund body) {
    if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw invalid("Idempotency-Key must be 8-100 letters, digits, '_' or '-'");
    }
    return PaymentFacts.of(cases.refund(bookingId, idempotencyKey, body.amount(), body.reason()));
  }

  private static ApiException invalid(String detail) {
    return ApiException.badRequest("validation-failed", "Validation failed", detail);
  }
}
