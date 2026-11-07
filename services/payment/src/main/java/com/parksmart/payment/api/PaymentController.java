package com.parksmart.payment.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.security.Role;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CursorPage;
import com.parksmart.payment.application.PaymentService;
import com.parksmart.payment.application.PayoutService;
import com.parksmart.payment.application.port.LedgerRepository;
import com.parksmart.payment.domain.PaymentIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/payment.yaml. Driver/host/admin checks happen here (roles) and in the services. */
@RestController
class PaymentController {

  private static final java.util.regex.Pattern KEY =
      java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,100}");

  private final PaymentService payments;
  private final PayoutService payouts;

  PaymentController(PaymentService payments, PayoutService payouts) {
    this.payments = payments;
    this.payouts = payouts;
  }

  record AuthorizeRequest(@NotBlank @Size(min = 3, max = 255) String paymentMethodToken) {}

  record RefundRequest(
      @NotNull @Pattern(regexp = "\\d{1,8}(\\.\\d{1,2})?") String amount,
      @NotBlank @Size(max = 255) String reason) {}

  record IntentResponse(
      UUID id,
      UUID bookingId,
      String amount,
      String currency,
      PaymentIntent.Status status,
      String failureCode,
      String refundedAmount,
      Instant createdAt,
      Instant updatedAt) {

    static IntentResponse of(PaymentIntent i) {
      return new IntentResponse(
          i.id(),
          i.bookingId(),
          i.amount().toPlainString(),
          i.currency(),
          i.status(),
          i.failureCode(),
          i.refundedAmount().toPlainString(),
          i.createdAt(),
          i.updatedAt());
    }
  }

  record PayoutResponse(
      UUID id,
      String amount,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      Instant createdAt) {

    static PayoutResponse of(LedgerRepository.Payout p) {
      return new PayoutResponse(
          p.id(),
          p.amount().toPlainString(),
          p.currency(),
          p.periodStart(),
          p.periodEnd(),
          p.status(),
          p.createdAt());
    }
  }

  record EarningsSummary(
      String currency, String totalEarned, String totalPaidOut, String pendingPayout) {}

  @GetMapping("/api/v1/payments/intents")
  IntentResponse forBooking(@RequestParam UUID bookingId) {
    return IntentResponse.of(payments.forBooking(CurrentAccount.require(), bookingId));
  }

  @PostMapping("/api/v1/payments/intents/{intentId}/authorize")
  IntentResponse authorize(
      @PathVariable UUID intentId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AuthorizeRequest body) {
    return IntentResponse.of(
        payments.authorize(
            CurrentAccount.require().accountId(),
            intentId,
            checked(key),
            body.paymentMethodToken()));
  }

  @GetMapping("/api/v1/hosts/me/payouts")
  CursorPage<PayoutResponse> myPayouts(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    UUID host = CurrentAccount.requireRole(Role.HOST).accountId();
    return payouts.payouts(host, cursor, limit).map(PayoutResponse::of);
  }

  @GetMapping("/api/v1/hosts/me/earnings-summary")
  EarningsSummary earnings() {
    UUID host = CurrentAccount.requireRole(Role.HOST).accountId();
    LedgerRepository.Earnings e = payouts.earnings(host);
    return new EarningsSummary(
        "INR",
        e.totalEarned().toPlainString(),
        e.totalPaidOut().toPlainString(),
        e.pendingPayout().toPlainString());
  }

  @PostMapping("/admin/v1/payments/intents/{intentId}/refunds")
  IntentResponse adminRefund(
      @PathVariable UUID intentId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody RefundRequest body) {
    UUID admin = CurrentAccount.requireRole(Role.ADMIN).accountId();
    return IntentResponse.of(
        payments.adminRefund(
            admin, intentId, checked(key), new BigDecimal(body.amount()), body.reason()));
  }

  private static String checked(String key) {
    if (!KEY.matcher(key).matches()) {
      throw ApiException.badRequest(
          "validation-failed",
          "Validation failed",
          "Idempotency-Key must be 8-100 letters, digits, '_' or '-'");
    }
    return key;
  }
}
