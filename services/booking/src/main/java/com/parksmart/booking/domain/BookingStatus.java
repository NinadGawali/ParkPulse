package com.parksmart.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/** Booking states (booking.yaml). Active ones hold the slot (exclusion constraint). */
public enum BookingStatus {
  PENDING_PAYMENT,
  AWAITING_HOST_APPROVAL,
  CONFIRMED,
  CHECKED_IN,
  COMPLETED,
  CANCELLED_BY_DRIVER,
  CANCELLED_BY_HOST,
  DECLINED_BY_HOST,
  NO_SHOW,
  EXPIRED,
  PAYMENT_FAILED;

  public static final Set<BookingStatus> ACTIVE =
      EnumSet.of(PENDING_PAYMENT, AWAITING_HOST_APPROVAL, CONFIRMED, CHECKED_IN);

  public boolean isActive() {
    return ACTIVE.contains(this);
  }
}
