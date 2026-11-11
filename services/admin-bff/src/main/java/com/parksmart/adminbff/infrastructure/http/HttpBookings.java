package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** docs/openapi/booking.yaml: GET of one booking (visible to ADMIN). */
@Component
class HttpBookings implements Downstream.Bookings {

  private final ServiceClient client;

  HttpBookings(RestClient.Builder builder, ObjectMapper json, DownstreamProperties props) {
    this.client = new ServiceClient("booking", builder, json, props.bookingUrl(), props.timeout());
  }

  /** vehicleId is deliberately not read. */
  record BookingBody(
      UUID id,
      UUID listingId,
      UUID driverAccountId,
      UUID hostAccountId,
      String status,
      Instant startTime,
      Instant endTime,
      String pricePerHour,
      String totalAmount,
      String currency,
      String cancellationPolicy,
      String refundAmount,
      Instant createdAt,
      Instant updatedAt) {}

  @Override
  public Optional<Facts.Booking> find(AdminCall call, UUID bookingId) {
    return client
        .find(call, BookingBody.class, "/api/v1/bookings/{id}", bookingId)
        .map(b ->
            client.convert(() ->
                new Facts.Booking(
                    b.id(),
                    b.listingId(),
                    b.driverAccountId(),
                    b.hostAccountId(),
                    b.status(),
                    b.startTime(),
                    b.endTime(),
                    money(b.pricePerHour()),
                    new BigDecimal(b.totalAmount()),
                    b.currency(),
                    b.cancellationPolicy(),
                    money(b.refundAmount()),
                    b.createdAt(),
                    b.updatedAt())));
  }

  private static BigDecimal money(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
