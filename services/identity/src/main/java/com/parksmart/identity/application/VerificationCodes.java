package com.parksmart.identity.application;

import com.parksmart.common.events.identity.VerificationCodeIssued;
import com.parksmart.identity.application.port.DevCodeSink;
import com.parksmart.identity.application.port.EventOutbox;
import com.parksmart.identity.application.port.Secrets;
import com.parksmart.identity.application.port.VerificationTokenRepository;
import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Issues one-time codes: stores only the hash, sends the value via the private topic. */
@Component
class VerificationCodes {

  private final VerificationTokenRepository tokens;
  private final Secrets secrets;
  private final EventOutbox outbox;
  private final Optional<DevCodeSink> devSink;
  private final Clock clock;

  VerificationCodes(
      VerificationTokenRepository tokens,
      Secrets secrets,
      EventOutbox outbox,
      Optional<DevCodeSink> devSink,
      Clock clock) {
    this.tokens = tokens;
    this.secrets = secrets;
    this.outbox = outbox;
    this.devSink = devSink;
    this.clock = clock;
  }

  /** Replaces any outstanding code of this purpose. Call inside a transaction. */
  void issue(UUID accountId, VerificationPurpose purpose, String destination) {
    Instant now = clock.instant();
    tokens.invalidateActive(accountId, purpose, now);
    String secret =
        purpose == VerificationPurpose.PASSWORD_RESET
            ? secrets.newOpaqueToken()
            : secrets.newSixDigitCode();
    VerificationToken token =
        VerificationToken.issue(
            UUID.randomUUID(), accountId, purpose, secrets.hash(secret), destination, now);
    tokens.insert(token);
    String channel = purpose == VerificationPurpose.PHONE_VERIFY ? "SMS" : "EMAIL";
    outbox.publish(
        new VerificationCodeIssued(
            accountId, purpose.name(), channel, destination, secret, token.expiresAt()));
    devSink.ifPresent(sink -> sink.accept(accountId, purpose, secret));
  }
}
