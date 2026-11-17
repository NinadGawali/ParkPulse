package com.parksmart.payment.application;

import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.payment.application.port.IntentRepository;
import com.parksmart.payment.application.port.IntentRepository.StoredKey;
import com.parksmart.payment.domain.DomainException;
import com.parksmart.payment.domain.PaymentIntent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Client-facing payment calls: read the intent, authorize it, admin refunds. */
@Service
public class PaymentService {

  static final String AUTHORIZE = "AUTHORIZE";
  static final String ADMIN_REFUND = "ADMIN_REFUND";

  private final IntentRepository intents;
  private final Money money;
  private final Clock clock;

  public PaymentService(IntentRepository intents, Money money, Clock clock) {
    this.intents = intents;
    this.money = money;
    this.clock = clock;
  }

  /** The booking's driver (or an admin); 404 until the intent exists (the client polls). */
  @Transactional(readOnly = true)
  public PaymentIntent forBooking(AuthenticatedAccount caller, UUID bookingId) {
    PaymentIntent intent =
        intents.findByBooking(bookingId).orElseThrow(() -> ApiException.notFound("Payment"));
    if (!intent.isDriver(caller.accountId()) && !caller.isAdmin()) {
      throw ApiException.forbidden("Only the booking's driver may see its payment");
    }
    return intent;
  }

  /**
   * Same key + same token: the stored result. A new key on an intent that is no longer CREATED: its
   * current state, without calling the processor again. After the deadline or once VOIDED: 409
   * {@code payment-window-closed}.
   */
  @Transactional
  public PaymentIntent authorize(
      UUID callerId, UUID intentId, String idempotencyKey, String paymentMethodToken) {
    PaymentIntent intent =
        intents.lockById(intentId).orElseThrow(() -> ApiException.notFound("Payment"));
    if (!intent.isDriver(callerId)) {
      throw ApiException.forbidden("Only the booking's driver may pay");
    }
    String hash = hash(intentId + "|" + paymentMethodToken);
    Optional<StoredKey> earlier = intents.findKey(AUTHORIZE, callerId, idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), hash, intent);
    }
    intent.checkAuthorizable(clock.instant());
    if (intent.status() == PaymentIntent.Status.CREATED) {
      money.authorize(intent, paymentMethodToken);
    }
    intents.saveKey(AUTHORIZE, callerId, idempotencyKey, hash, intentId, clock.instant());
    return intent;
  }

  /** Manual (dispute) refund of a captured intent, up to what is left. */
  @Transactional
  public PaymentIntent adminRefund(
      AuthenticatedAccount admin,
      UUID intentId,
      String idempotencyKey,
      BigDecimal amount,
      String reason) {
    if (!admin.isAdmin()) {
      throw ApiException.forbidden("Requires role ADMIN");
    }
    UUID adminId = admin.accountId();
    PaymentIntent intent =
        intents.lockById(intentId).orElseThrow(() -> ApiException.notFound("Payment"));
    String hash = hash(intentId + "|" + amount.toPlainString() + "|" + reason);
    Optional<StoredKey> earlier = intents.findKey(ADMIN_REFUND, adminId, idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), hash, intent);
    }
    money.refund(intent, amount, "ADMIN", adminId, reason);
    intents.saveKey(ADMIN_REFUND, adminId, idempotencyKey, hash, intentId, clock.instant());
    return intent;
  }

  private static PaymentIntent replay(StoredKey earlier, String hash, PaymentIntent intent) {
    if (!earlier.requestHash().equals(hash) || !earlier.intentId().equals(intent.id())) {
      throw new DomainException(
          "idempotency-key-reused", "This Idempotency-Key was used for a different request");
    }
    return intent;
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
