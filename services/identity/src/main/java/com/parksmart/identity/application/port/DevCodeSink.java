package com.parksmart.identity.application.port;

import com.parksmart.identity.domain.VerificationPurpose;
import java.util.UUID;

/**
 * Local development only: receives verification codes so a developer can complete flows before
 * Notification Service exists. Never present in the {@code aws} profile.
 */
public interface DevCodeSink {

  void accept(UUID accountId, VerificationPurpose purpose, String code);
}
