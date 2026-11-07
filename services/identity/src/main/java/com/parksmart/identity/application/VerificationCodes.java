package com.parksmart.identity.application;

import com.parksmart.common.events.identity.VerificationCodeIssued;
import com.parksmart.common.web.ApiException;
import com.parksmart.identity.application.port.DevCodeSink;
import com.parksmart.identity.application.port.EventOutbox;
import com.parksmart.identity.application.port.Secrets;
import com.parksmart.identity.application.port.VerificationTokenRepository;
import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Issues one-time codes: stores only the hash, sends the value via the private topic. */
@Component
class VerificationCodes {

  private final VerificationTokenRepository tokens;
  private final Secrets secrets;
  private final EventOutbox outbox;
  private final Optional<DevCodeSink> devSink;
  private final IdentityProperties.Codes limits;
  private final Clock clock;

  VerificationCodes(
      VerificationTokenRepository tokens,
      Secrets secrets,
      EventOutbox outbox,
      Optional<DevCodeSink> devSink,
      IdentityProperties properties,
      Clock clock) {
    this.tokens = tokens;
    this.secrets = secrets;
    this.outbox = outbox;
    this.devSink = devSink;
    this.limits = properties.codes();
    this.clock = clock;
  }

  /**
   * Issues a code, or throws 429 {@code code-recently-sent} when the resend cooldown or hourly cap
   * is hit (with 5 attempts per code and 5 codes per hour, at most 25 guesses per hour). Call
   * inside a transaction.
   */
  void issue(UUID accountId, VerificationPurpose purpose, String destination) {
    if (!issueIfAllowed(accountId, purpose, destination)) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "code-recently-sent",
          "Code recently sent",
          "Wait before requesting another code");
    }
  }

  /**
   * Like {@link #issue} but silently does nothing when throttled (used where the caller must not
   * learn anything, e.g. forgot-password).
   *
   * @return false if throttled
   */
  boolean issueIfAllowed(UUID accountId, VerificationPurpose purpose, String destination) {
    Instant now = clock.instant();
    VerificationTokenRepository.IssueStats recent =
        tokens.issueStats(accountId, purpose, now.minus(Duration.ofHours(1)));
    if (recent.issuedSince() >= limits.maxPerHour()
        || (recent.lastIssuedAt() != null
            && now.isBefore(recent.lastIssuedAt().plus(limits.resendCooldown())))) {
      return false;
    }
    doIssue(accountId, purpose, destination, now);
    return true;
  }

  private void doIssue(
      UUID accountId, VerificationPurpose purpose, String destination, Instant now) {
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
