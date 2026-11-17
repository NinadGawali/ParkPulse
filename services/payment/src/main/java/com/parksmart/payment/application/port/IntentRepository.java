package com.parksmart.payment.application.port;

import com.parksmart.payment.domain.PaymentIntent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IntentRepository {

  Optional<PaymentIntent> findById(UUID id);

  Optional<PaymentIntent> findByBooking(UUID bookingId);

  /** Locks the intent row until the transaction ends (all money operations hold it). */
  Optional<PaymentIntent> lockById(UUID id);

  Optional<PaymentIntent> lockByBooking(UUID bookingId);

  /** One intent per booking: returns false if the booking already has one. */
  boolean insertIfAbsent(PaymentIntent intent);

  /** Writes the intent's state; the caller holds the row lock. */
  void update(PaymentIntent intent);

  record StoredKey(String requestHash, UUID intentId) {}

  Optional<StoredKey> findKey(String scope, UUID actorId, String key);

  void saveKey(
      String scope, UUID actorId, String key, String requestHash, UUID intentId, Instant at);
}
