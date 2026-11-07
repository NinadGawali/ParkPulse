package com.parksmart.identity.infrastructure;

import com.parksmart.identity.application.port.DevCodeSink;
import com.parksmart.identity.domain.VerificationPurpose;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Logs verification codes so flows can be completed locally before Notification Service exists.
 * Refuses to start unless the {@code local} or {@code test} profile is active.
 */
@Component
@ConditionalOnProperty("parksmart.identity.dev-log-verification-codes")
class LoggingDevCodeSink implements DevCodeSink {

  private static final Logger log = LoggerFactory.getLogger(LoggingDevCodeSink.class);

  LoggingDevCodeSink(Environment environment) {
    if (!environment.acceptsProfiles(Profiles.of("local | test"))) {
      throw new IllegalStateException(
          "dev-log-verification-codes is only allowed with the local or test profile");
    }
  }

  @Override
  public void accept(UUID accountId, VerificationPurpose purpose, String code) {
    log.warn("DEV ONLY - {} code for account {}: {}", purpose, accountId, code);
  }
}
