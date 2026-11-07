package com.parksmart.identity.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code parksmart.identity.*}.
 *
 * @param issuer {@code iss} claim of issued tokens
 * @param audience {@code aud} claim of issued tokens
 * @param accessTokenTtl access token lifetime
 * @param refreshTokenTtl refresh token lifetime
 * @param codePepper secret key for hashing codes and tokens (from the environment, never default)
 * @param jwt signing key settings
 * @param devLogVerificationCodes local profile only: log codes instead of waiting for Notification
 *     Service
 */
@Validated
@ConfigurationProperties("parksmart.identity")
public record IdentityProperties(
    String issuer,
    String audience,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    String codePepper,
    Jwt jwt,
    boolean devLogVerificationCodes) {

  public IdentityProperties {
    require(issuer, "parksmart.identity.issuer");
    require(codePepper, "parksmart.identity.code-pepper");
    if (codePepper.length() < 32) {
      throw new IllegalStateException("parksmart.identity.code-pepper must be at least 32 chars");
    }
    audience = audience == null || audience.isBlank() ? "parksmart-api" : audience;
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    jwt = jwt == null ? new Jwt(null, false) : jwt;
  }

  /**
   * @param privateKeyPem RSA private key, PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----})
   * @param generateEphemeralKey local/test only: generate a throwaway key when none is set
   */
  public record Jwt(String privateKeyPem, boolean generateEphemeralKey) {}

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set");
    }
  }
}
