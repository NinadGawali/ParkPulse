package com.parksmart.profile.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code parksmart.profile.*}.
 *
 * @param uploadUrlTtl how long a presigned photo upload URL stays valid
 * @param allowFakeProcessor accept {@code FAKE} payout accounts (local/test only; false on aws)
 */
@ConfigurationProperties("parksmart.profile")
public record ProfileProperties(Duration uploadUrlTtl, boolean allowFakeProcessor) {

  public ProfileProperties {
    if (uploadUrlTtl == null) {
      uploadUrlTtl = Duration.ofMinutes(10);
    }
  }
}
