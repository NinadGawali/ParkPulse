package com.parksmart.review.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.web.CursorPage;
import com.parksmart.review.application.ReviewService;
import com.parksmart.review.application.port.ReviewRepository.Aggregate;
import com.parksmart.review.domain.Review;
import com.parksmart.review.domain.Review.TargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/review.yaml. DTOs live here; the domain record never leaves the service. */
@RestController
class ReviewController {

  private final ReviewService reviews;

  ReviewController(ReviewService reviews) {
    this.reviews = reviews;
  }

  record SubmitReviewRequest(
      @NotNull UUID bookingId,
      @NotNull @Min(1) @Max(5) Integer rating,
      @Size(max = 1000) String comment) {}

  record HideRequest(@NotBlank @Size(max = 255) String reason) {}

  record ReviewResponse(
      UUID id,
      UUID bookingId,
      String direction,
      String targetType,
      UUID targetId,
      int rating,
      String comment,
      Instant createdAt) {
    static ReviewResponse of(Review r) {
      return new ReviewResponse(
          r.id(),
          r.bookingId(),
          r.direction().name(),
          r.targetType().name(),
          r.targetId(),
          r.rating(),
          r.comment(),
          r.createdAt());
    }
  }

  record SummaryResponse(String targetType, UUID targetId, String avgRating, int reviewCount) {}

  @PostMapping("/api/v1/reviews")
  @ResponseStatus(HttpStatus.CREATED)
  ReviewResponse submit(@Valid @RequestBody SubmitReviewRequest request) {
    UUID caller = CurrentAccount.require().accountId();
    return ReviewResponse.of(
        reviews.submit(caller, request.bookingId(), request.rating(), request.comment()));
  }

  @GetMapping("/api/v1/reviews")
  CursorPage<ReviewResponse> list(
      @RequestParam TargetType targetType,
      @RequestParam UUID targetId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    requireLoginForDriverTarget(targetType);
    return reviews.published(targetType, targetId, cursor, limit).map(ReviewResponse::of);
  }

  @GetMapping("/api/v1/reviews/summary")
  SummaryResponse summary(@RequestParam TargetType targetType, @RequestParam UUID targetId) {
    requireLoginForDriverTarget(targetType);
    Aggregate a = reviews.summary(targetType, targetId);
    return new SummaryResponse(
        targetType.name(), targetId, a.avg() == null ? null : a.avg().toPlainString(), a.count());
  }

  /** Listing reviews are public; host-written comments about drivers need a signed-in caller. */
  private static void requireLoginForDriverTarget(TargetType type) {
    if (type == TargetType.DRIVER && CurrentAccount.find().isEmpty()) {
      throw com.parksmart.common.web.ApiException.unauthorized("Sign in to read driver reviews");
    }
  }

  @PostMapping("/admin/v1/reviews/{reviewId}/hide")
  ReviewResponse hide(@PathVariable UUID reviewId, @Valid @RequestBody HideRequest request) {
    return ReviewResponse.of(reviews.hide(CurrentAccount.require(), reviewId, request.reason()));
  }
}
