package com.parksmart.adminbff.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** docs/openapi/payment.yaml: intent of a booking (ADMIN may read it) and the admin refund. */
@Component
class HttpPayments implements Downstream.Payments {

  private final ServiceClient client;

  HttpPayments(RestClient.Builder builder, ObjectMapper json, DownstreamProperties props) {
    this.client = new ServiceClient("payment", builder, json, props.paymentUrl(), props.timeout());
  }

  record IntentBody(
      UUID id,
      UUID bookingId,
      String amount,
      String currency,
      String status,
      String failureCode,
      String refundedAmount,
      Instant updatedAt) {}

  record RefundBody(String amount, String reason) {}

  @Override
  public Optional<Facts.Payment> findForBooking(AdminCall call, UUID bookingId) {
    return client
        .find(call, IntentBody.class, "/api/v1/payments/intents?bookingId={id}", bookingId)
        .map(b -> client.convert(() -> facts(b)));
  }

  @Override
  public Facts.Payment refund(
      AdminCall call, UUID intentId, String idempotencyKey, String amount, String reason) {
    IntentBody body =
        client.post(
            call,
            new RefundBody(amount, reason),
            Map.of("Idempotency-Key", idempotencyKey),
            IntentBody.class,
            "/admin/v1/payments/intents/{id}/refunds",
            intentId);
    return client.convert(() -> facts(body));
  }

  private static Facts.Payment facts(IntentBody b) {
    return new Facts.Payment(
        b.id(),
        b.bookingId(),
        new BigDecimal(b.amount()),
        b.currency(),
        b.status(),
        b.failureCode(),
        new BigDecimal(b.refundedAmount()),
        b.updatedAt());
  }
}
