package com.parksmart.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code parksmart.security.jwt.*}. Resource servers set {@code jwks-uri} (Identity's {@code
 * /.well-known/jwks.json}); Identity itself provides a decoder from its own key instead.
 *
 * @param issuer expected {@code iss}; required
 * @param audience expected {@code aud}
 * @param jwksUri where to fetch the public keys
 */
@ConfigurationProperties("parksmart.security.jwt")
public record JwtProperties(String issuer, String audience, String jwksUri) {

  public JwtProperties {
    if (audience == null || audience.isBlank()) {
      audience = JwtClaims.DEFAULT_AUDIENCE;
    }
  }
}
