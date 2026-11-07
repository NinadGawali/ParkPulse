package com.parksmart.common.events;

import com.parksmart.common.events.booking.BookingAwaitingHostApproval;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingCheckedIn;
import com.parksmart.common.events.booking.BookingCompleted;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.booking.BookingDeclined;
import com.parksmart.common.events.booking.BookingExpired;
import com.parksmart.common.events.booking.BookingNoShow;
import com.parksmart.common.events.booking.BookingRequested;
import com.parksmart.common.events.identity.AccountRegistered;
import com.parksmart.common.events.identity.AccountSuspended;
import com.parksmart.common.events.identity.EmailVerified;
import com.parksmart.common.events.identity.PhoneVerified;
import com.parksmart.common.events.identity.RoleGranted;
import com.parksmart.common.events.identity.VerificationCodeIssued;
import com.parksmart.common.events.listing.AvailabilityChanged;
import com.parksmart.common.events.listing.ListingArchived;
import com.parksmart.common.events.listing.ListingCreated;
import com.parksmart.common.events.listing.ListingPaused;
import com.parksmart.common.events.listing.ListingPublished;
import com.parksmart.common.events.listing.ListingRejected;
import com.parksmart.common.events.listing.ListingSubmittedForReview;
import com.parksmart.common.events.listing.ListingUpdated;
import com.parksmart.common.events.payment.PaymentAuthorized;
import com.parksmart.common.events.payment.PaymentCaptured;
import com.parksmart.common.events.payment.PaymentFailed;
import com.parksmart.common.events.payment.PaymentRefunded;
import com.parksmart.common.events.payment.PaymentVoided;
import com.parksmart.common.events.payment.PayoutCompleted;
import com.parksmart.common.events.payment.PayoutFailed;
import com.parksmart.common.events.profile.ProfileUpdated;
import com.parksmart.common.events.profile.VehicleAdded;
import com.parksmart.common.events.profile.VehicleRemoved;
import com.parksmart.common.events.review.RatingAggregateUpdated;
import com.parksmart.common.events.review.ReviewSubmitted;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every event type ParkSmart publishes, with its version, topic, and payload record. */
public final class EventCatalog {

  public static final List<EventDefinition> ALL =
      List.of(
          v1(AccountRegistered.class, Topics.IDENTITY_ACCOUNT),
          v1(EmailVerified.class, Topics.IDENTITY_ACCOUNT),
          v1(PhoneVerified.class, Topics.IDENTITY_ACCOUNT),
          v1(RoleGranted.class, Topics.IDENTITY_ACCOUNT),
          v1(AccountSuspended.class, Topics.IDENTITY_ACCOUNT),
          v1(VerificationCodeIssued.class, Topics.IDENTITY_VERIFICATION_DELIVERY),
          v1(ProfileUpdated.class, Topics.PROFILE),
          v1(VehicleAdded.class, Topics.PROFILE),
          v1(VehicleRemoved.class, Topics.PROFILE),
          v1(ListingCreated.class, Topics.LISTING),
          v1(ListingSubmittedForReview.class, Topics.LISTING),
          v1(ListingPublished.class, Topics.LISTING),
          v1(ListingUpdated.class, Topics.LISTING),
          v1(AvailabilityChanged.class, Topics.LISTING),
          v1(ListingPaused.class, Topics.LISTING),
          v1(ListingRejected.class, Topics.LISTING),
          v1(ListingArchived.class, Topics.LISTING),
          v1(BookingRequested.class, Topics.BOOKING),
          v1(BookingAwaitingHostApproval.class, Topics.BOOKING),
          v1(BookingConfirmed.class, Topics.BOOKING),
          v1(BookingDeclined.class, Topics.BOOKING),
          v1(BookingCancelled.class, Topics.BOOKING),
          v1(BookingExpired.class, Topics.BOOKING),
          v1(BookingCheckedIn.class, Topics.BOOKING),
          v1(BookingCompleted.class, Topics.BOOKING),
          v1(BookingNoShow.class, Topics.BOOKING),
          v1(PaymentAuthorized.class, Topics.PAYMENT),
          v1(PaymentFailed.class, Topics.PAYMENT),
          v1(PaymentCaptured.class, Topics.PAYMENT),
          v1(PaymentVoided.class, Topics.PAYMENT),
          v1(PaymentRefunded.class, Topics.PAYMENT),
          v1(PayoutCompleted.class, Topics.PAYOUT),
          v1(PayoutFailed.class, Topics.PAYOUT),
          v1(ReviewSubmitted.class, Topics.REVIEW),
          v1(RatingAggregateUpdated.class, Topics.REVIEW));

  private static final Map<Class<?>, EventDefinition> BY_CLASS =
      ALL.stream()
          .collect(
              Collectors.toUnmodifiableMap(EventDefinition::payloadClass, Function.identity()));

  private static final Map<String, EventDefinition> BY_TYPE_AND_VERSION =
      ALL.stream()
          .collect(
              Collectors.toUnmodifiableMap(d -> key(d.type(), d.version()), Function.identity()));

  private EventCatalog() {}

  public static EventDefinition forPayload(Class<?> payloadClass) {
    EventDefinition definition = BY_CLASS.get(payloadClass);
    if (definition == null) {
      throw new IllegalArgumentException(
          payloadClass.getName() + " is not registered in EventCatalog");
    }
    return definition;
  }

  public static Optional<EventDefinition> find(String type, int version) {
    return Optional.ofNullable(BY_TYPE_AND_VERSION.get(key(type, version)));
  }

  private static String key(String type, int version) {
    return type + "/v" + version;
  }

  private static EventDefinition v1(Class<? extends EventPayload> payload, String topic) {
    return new EventDefinition(payload.getSimpleName(), 1, topic, payload);
  }
}
