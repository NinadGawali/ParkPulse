package com.parksmart.booking.api;

import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.booking.domain.CancellationPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies (docs/openapi/booking.yaml). */
final class BookingDtos {

  private BookingDtos() {}

  record CreateBooking(
      @NotNull UUID listingId,
      UUID vehicleId,
      @NotNull Instant startTime,
      @NotNull Instant endTime) {}

  record Reason(@NotBlank @Size(max = 255) String reason) {}

  record BookingResponse(
      UUID id,
      UUID listingId,
      UUID driverAccountId,
      UUID hostAccountId,
      UUID vehicleId,
      BookingStatus status,
      Instant startTime,
      Instant endTime,
      String pricePerHour,
      String totalAmount,
      String currency,
      boolean instantBook,
      CancellationPolicy cancellationPolicy,
      int bufferMinutes,
      Instant paymentDueBy,
      Instant hostDecisionDueBy,
      String refundAmount,
      Instant createdAt,
      Instant updatedAt) {

    static BookingResponse of(Booking b) {
      return new BookingResponse(
          b.id(),
          b.listingId(),
          b.driverAccountId(),
          b.hostAccountId(),
          b.vehicleId(),
          b.status(),
          b.startTime(),
          b.endTime(),
          money(b.terms().pricePerHour()),
          money(b.totalAmount()),
          b.terms().currency(),
          b.terms().instantBook(),
          b.terms().cancellationPolicy(),
          b.terms().bufferMinutes(),
          b.paymentDueBy(),
          b.hostDecisionDueBy(),
          b.refundAmount() == null ? null : money(b.refundAmount()),
          b.createdAt(),
          b.updatedAt());
    }

    private static String money(BigDecimal value) {
      return value.setScale(2).toPlainString();
    }
  }
}
