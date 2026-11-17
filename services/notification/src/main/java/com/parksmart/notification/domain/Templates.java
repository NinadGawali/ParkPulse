package com.parksmart.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Message texts. One method per notice; every variable is cleaned by {@link Message}. */
public final class Templates {

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private Templates() {}

  static String when(Instant t) {
    return t == null ? "" : WHEN.format(t);
  }

  static String money(BigDecimal amount, String currency) {
    return amount == null ? "" : amount.setScale(2) + " " + (currency == null ? "INR" : currency);
  }

  // ---------------------------------------------------------------- account

  public static Message code(String purpose, String code, Instant expiresAt) {
    String what =
        switch (purpose) {
          case "EMAIL_VERIFY" -> "verify your email";
          case "PHONE_VERIFY" -> "verify your phone";
          case "PASSWORD_RESET" -> "reset your password";
          default -> "continue";
        };
    return new Message(
        "code." + purpose.toLowerCase(java.util.Locale.ROOT),
        "Your ParkSmart code",
        "Use "
            + code
            + " to "
            + what
            + ". It expires at "
            + when(expiresAt)
            + ". If you did not ask for this, ignore this message.");
  }

  public static Message suspended(String reason) {
    return new Message(
        "account.suspended",
        "Your account was suspended",
        "Your ParkSmart account was suspended: "
            + Message.clean(reason, 255)
            + ". Contact support if you think this is a mistake.");
  }

  // ---------------------------------------------------------------- bookings

  public static Message awaitingApproval(Instant start, Instant hostDue) {
    return new Message(
        "booking.awaiting_host",
        "New booking request",
        "A driver asked to book your space from "
            + when(start)
            + ". Approve or decline by "
            + when(hostDue)
            + ", or it expires.");
  }

  public static Message confirmedDriver(Instant start, Instant end, BigDecimal total, String ccy) {
    return new Message(
        "booking.confirmed.driver",
        "Booking confirmed",
        "Your parking is booked from "
            + when(start)
            + " to "
            + when(end)
            + ". Total "
            + money(total, ccy)
            + ".");
  }

  public static Message confirmedHost(Instant start, Instant end) {
    return new Message(
        "booking.confirmed.host",
        "New booking",
        "Your space is booked from " + when(start) + " to " + when(end) + ".");
  }

  public static Message declined(String reason) {
    return new Message(
        "booking.declined",
        "Booking declined",
        "The host declined your request ("
            + Message.clean(reason, 255)
            + "). Your payment hold is released.");
  }

  public static Message cancelled(String by, String reason, BigDecimal refund, String ccy) {
    String who = "DRIVER".equals(by) ? "The driver" : "The host";
    return new Message(
        "booking.cancelled",
        "Booking cancelled",
        who
            + " cancelled the booking ("
            + Message.clean(reason, 255)
            + "). Refund: "
            + money(refund, ccy)
            + ".");
  }

  public static Message expired(String fromStatus) {
    String why =
        "AWAITING_HOST_APPROVAL".equals(fromStatus)
            ? "the host did not answer in time"
            : "payment was not completed in time";
    return new Message(
        "booking.expired", "Booking expired", "Your booking expired because " + why + ".");
  }

  public static Message completed(Instant reviewBy) {
    return new Message(
        "booking.completed",
        "Thanks for parking",
        "Your booking is complete. You can leave a review until " + when(reviewBy) + ".");
  }

  public static Message noShow() {
    return new Message(
        "booking.no_show", "Missed check-in", "Nobody checked in for the booking in time.");
  }

  // ---------------------------------------------------------------- payments

  public static Message captureFailed() {
    return new Message(
        "payment.capture_failed",
        "Payment failed",
        "The payment for the booking could not be collected, so the booking was cancelled.");
  }

  public static Message refunded(BigDecimal amount, String ccy) {
    return new Message(
        "payment.refunded", "Refund issued", "We refunded " + money(amount, ccy) + ".");
  }

  public static Message payoutCompleted(BigDecimal amount, String ccy) {
    return new Message(
        "payout.completed", "Payout sent", "We sent you " + money(amount, ccy) + ".");
  }

  public static Message payoutFailed(BigDecimal amount, String ccy) {
    return new Message(
        "payout.failed",
        "Payout failed",
        "We could not send your payout of "
            + money(amount, ccy)
            + ". Check your payout account; we will retry next week.");
  }

  // ---------------------------------------------------------------- listings

  public static Message toModerate(String listingId) {
    return new Message(
        "listing.review_queue",
        "Listing waiting for review",
        "Listing " + listingId + " was submitted for review.");
  }

  public static Message published(String title) {
    return new Message(
        "listing.published",
        "Your listing is live",
        "\"" + Message.clean(title, 255) + "\" is now visible to drivers.");
  }

  public static Message rejected(String reason) {
    return new Message(
        "listing.rejected",
        "Listing not approved",
        "Your listing was not approved: "
            + Message.clean(reason, 500)
            + ". Edit it and submit again.");
  }

  public static Message reviewed(int rating) {
    return new Message(
        "review.received", "You got a review", "A host rated you " + rating + " out of 5.");
  }
}
