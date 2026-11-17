package com.parksmart.common.events;

/** Kafka topic names. Each consumer also gets a dead-letter topic: {@code <topic> + DLT_SUFFIX}. */
public final class Topics {

  public static final String IDENTITY_ACCOUNT = "identity.account.v1";

  /**
   * Contact details and one-time codes for Notification Service only. Short retention, restricted
   * ACLs; never consumed by any other service.
   */
  public static final String IDENTITY_PRIVATE = "identity.private.v1";

  public static final String PROFILE = "profile.profile.v1";
  public static final String LISTING = "listing.listing.v1";
  public static final String BOOKING = "booking.booking.v1";
  public static final String PAYMENT = "payment.payment.v1";
  public static final String PAYOUT = "payment.payout.v1";
  public static final String REVIEW = "review.review.v1";

  /** Spring Kafka's DeadLetterPublishingRecoverer default suffix. */
  public static final String DLT_SUFFIX = ".DLT";

  private Topics() {}

  public static String deadLetterTopic(String topic) {
    return topic + DLT_SUFFIX;
  }
}
