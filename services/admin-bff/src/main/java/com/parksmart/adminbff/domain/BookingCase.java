package com.parksmart.adminbff.domain;

import com.parksmart.adminbff.domain.Facts.Booking;
import com.parksmart.adminbff.domain.Facts.Listing;
import com.parksmart.adminbff.domain.Facts.Party;
import com.parksmart.adminbff.domain.Facts.Payment;
import java.util.Objects;

/**
 * Support/dispute view of one booking. The booking is required; payment, listing and the two public
 * profiles are secondary. Facts that do not belong to this booking are dropped and mark the view
 * {@code partial}, like a failed lookup.
 */
public record BookingCase(
    Booking booking, Payment payment, Listing listing, Party driver, Party host, boolean partial) {

  public BookingCase {
    Objects.requireNonNull(booking, "booking");
  }

  public static BookingCase compose(
      Booking booking,
      Lookup<Payment> payment,
      Lookup<Listing> listing,
      Lookup<Party> driver,
      Lookup<Party> host) {
    Lookup<Payment> p = payment.requireThat(i -> i.bookingId().equals(booking.bookingId()));
    Lookup<Listing> l = listing.requireThat(x -> x.listingId().equals(booking.listingId()));
    Lookup<Party> d = driver.requireThat(x -> x.accountId().equals(booking.driverAccountId()));
    Lookup<Party> h = host.requireThat(x -> x.accountId().equals(booking.hostAccountId()));
    return new BookingCase(
        booking,
        p.orNull(),
        l.orNull(),
        d.orNull(),
        h.orNull(),
        p.isFailed() || l.isFailed() || d.isFailed() || h.isFailed());
  }
}
