package com.parksmart.adminbff.application;

import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.BookingCase;
import com.parksmart.adminbff.domain.Facts;
import com.parksmart.adminbff.domain.Lookup;
import com.parksmart.common.web.ApiException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Support/dispute view of a booking and the manual refund. Every method requires ADMIN. */
@Service
public class BookingCaseService {

  private final Downstream.Bookings bookings;
  private final Downstream.Payments payments;
  private final Downstream.Listings listings;
  private final Downstream.Profiles profiles;
  private final ParallelLookups lookups;

  BookingCaseService(
      Downstream.Bookings bookings,
      Downstream.Payments payments,
      Downstream.Listings listings,
      Downstream.Profiles profiles,
      ParallelLookups lookups) {
    this.bookings = bookings;
    this.payments = payments;
    this.listings = listings;
    this.profiles = profiles;
    this.lookups = lookups;
  }

  /** 404 when Booking does not know the booking; the other parts are looked up in parallel. */
  public BookingCase find(UUID bookingId) {
    AdminCall call = Admins.require();
    Facts.Booking booking =
        bookings.find(call, bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    CompletableFuture<Lookup<Facts.Payment>> payment =
        lookups.lookup("payment", () -> payments.findForBooking(call, bookingId));
    CompletableFuture<Lookup<Facts.Listing>> listing =
        lookups.lookup("listing", () -> listings.find(call, booking.listingId()));
    CompletableFuture<Lookup<Facts.Party>> driver =
        lookups.lookup("profile", () -> profiles.find(call, booking.driverAccountId()));
    CompletableFuture<Lookup<Facts.Party>> host =
        lookups.lookup("profile", () -> profiles.find(call, booking.hostAccountId()));
    return BookingCase.compose(
        booking,
        ParallelLookups.join(payment),
        ParallelLookups.join(listing),
        ParallelLookups.join(driver),
        ParallelLookups.join(host));
  }

  /**
   * Refunds part or all of what was captured for the booking. Payment decides whether that is
   * allowed and stores the result under the admin's Idempotency-Key, so a retry with the same key
   * and body never refunds twice.
   */
  public Facts.Payment refund(UUID bookingId, String idempotencyKey, String amount, String reason) {
    AdminCall call = Admins.require();
    Facts.Payment intent =
        payments
            .findForBooking(call, bookingId)
            .filter(i -> i.bookingId().equals(bookingId))
            .orElseThrow(() ->
                new ApiException(
                    HttpStatus.NOT_FOUND,
                    "payment-not-found",
                    "Payment not found",
                    "This booking has no payment"));
    return payments.refund(call, intent.intentId(), idempotencyKey, amount, reason);
  }
}
