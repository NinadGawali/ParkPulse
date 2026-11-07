package com.parksmart.notification.application;

import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.booking.BookingAwaitingHostApproval;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingCompleted;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.booking.BookingDeclined;
import com.parksmart.common.events.booking.BookingExpired;
import com.parksmart.common.events.booking.BookingNoShow;
import com.parksmart.common.events.identity.AccountSuspended;
import com.parksmart.common.events.identity.ContactDetailsChanged;
import com.parksmart.common.events.identity.RoleGranted;
import com.parksmart.common.events.identity.VerificationCodeIssued;
import com.parksmart.common.events.listing.ListingPublished;
import com.parksmart.common.events.listing.ListingRejected;
import com.parksmart.common.events.listing.ListingSubmittedForReview;
import com.parksmart.common.events.payment.PaymentCaptureFailed;
import com.parksmart.common.events.payment.PaymentRefunded;
import com.parksmart.common.events.payment.PayoutCompleted;
import com.parksmart.common.events.payment.PayoutFailed;
import com.parksmart.common.events.profile.NotificationPreferencesChanged;
import com.parksmart.common.events.review.ReviewSubmitted;
import com.parksmart.notification.application.port.NotificationStore;
import com.parksmart.notification.application.port.NotificationStore.Channel;
import com.parksmart.notification.application.port.NotificationStore.Preferences;
import com.parksmart.notification.application.port.ProcessedEvents;
import com.parksmart.notification.domain.Templates;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What each event means for whom. Each event is handled once (processed_events). */
@Service
public class IncomingEvents {

  static final String CONSUMER = "notification-service";

  private final ProcessedEvents processed;
  private final NotificationStore store;
  private final Notifier notify;

  public IncomingEvents(ProcessedEvents processed, NotificationStore store, Notifier notify) {
    this.processed = processed;
    this.store = store;
    this.notify = notify;
  }

  @Transactional
  public void handle(UUID eventId, EventPayload event) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    switch (event) {
      // ---------------------------------------------------------- identity and profile
      case ContactDetailsChanged e ->
          store.saveContact(e.accountId(), e.email(), e.verifiedPhone(), e.changedAt());
      case NotificationPreferencesChanged e ->
          store.savePreferences(
              e.accountId(), new Preferences(e.email(), e.sms(), e.push()), e.changedAt());
      case RoleGranted e -> {
        if ("ADMIN".equals(e.role())) {
          store.addAdmin(e.accountId(), e.grantedAt());
        }
      }
      case VerificationCodeIssued e ->
          notify.code(
              eventId,
              e.accountId(),
              "SMS".equals(e.channel()) ? Channel.SMS : Channel.EMAIL,
              e.destination(),
              Templates.code(e.purpose(), e.code(), e.expiresAt()));
      case AccountSuspended e ->
          notify.toAccount(eventId, e.accountId(), Templates.suspended(e.reason()));

      // ---------------------------------------------------------- bookings
      case BookingAwaitingHostApproval e ->
          notify.toAccount(
              eventId,
              e.hostAccountId(),
              Templates.awaitingApproval(e.startTime(), e.hostDecisionDueBy()));
      case BookingConfirmed e -> {
        notify.toAccount(
            eventId,
            e.driverAccountId(),
            Templates.confirmedDriver(e.startTime(), e.endTime(), e.totalAmount(), e.currency()));
        notify.toAccount(
            eventId, e.hostAccountId(), Templates.confirmedHost(e.startTime(), e.endTime()));
      }
      case BookingDeclined e ->
          notify.toAccount(eventId, e.driverAccountId(), Templates.declined(e.reason()));
      case BookingCancelled e -> {
        var message =
            Templates.cancelled(e.cancelledBy(), e.reason(), e.refundAmount(), e.currency());
        notify.toAccount(eventId, e.driverAccountId(), message);
        notify.toAccount(eventId, e.hostAccountId(), message);
      }
      case BookingExpired e ->
          notify.toAccount(eventId, e.driverAccountId(), Templates.expired(e.fromStatus()));
      case BookingCompleted e ->
          notify.toAccount(
              eventId, e.driverAccountId(), Templates.completed(e.reviewWindowEndsAt()));
      case BookingNoShow e -> {
        notify.toAccount(eventId, e.driverAccountId(), Templates.noShow());
        notify.toAccount(eventId, e.hostAccountId(), Templates.noShow());
      }

      // ---------------------------------------------------------- payments
      case PaymentCaptureFailed e -> {
        notify.toAccount(eventId, e.driverAccountId(), Templates.captureFailed());
        notify.toAccount(eventId, e.hostAccountId(), Templates.captureFailed());
      }
      case PaymentRefunded e ->
          notify.toAccount(
              eventId, e.driverAccountId(), Templates.refunded(e.refundAmount(), e.currency()));
      case PayoutCompleted e ->
          notify.toAccount(
              eventId, e.hostAccountId(), Templates.payoutCompleted(e.amount(), e.currency()));
      case PayoutFailed e ->
          notify.toAccount(
              eventId, e.hostAccountId(), Templates.payoutFailed(e.amount(), e.currency()));

      // ---------------------------------------------------------- listings and reviews
      case ListingSubmittedForReview e -> {
        for (UUID admin : store.admins()) {
          notify.toAccount(eventId, admin, Templates.toModerate(e.listingId().toString()));
        }
      }
      case ListingPublished e ->
          notify.toAccount(
              eventId, e.listing().hostAccountId(), Templates.published(e.listing().title()));
      case ListingRejected e ->
          notify.toAccount(eventId, e.hostAccountId(), Templates.rejected(e.reason()));
      case ReviewSubmitted e -> {
        if ("DRIVER".equals(e.targetType())) {
          notify.toAccount(eventId, e.targetId(), Templates.reviewed(e.rating()));
        }
      }
      default -> {
        // Not a notice.
      }
    }
  }
}
