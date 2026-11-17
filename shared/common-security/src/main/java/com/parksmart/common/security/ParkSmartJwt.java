package com.parksmart.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Decoder validation and authority mapping shared by every service. */
public final class ParkSmartJwt {

  private ParkSmartJwt() {}

  /** Decoder that fetches keys from a JWKS endpoint and accepts RS256 only. */
  public static NimbusJwtDecoder jwksDecoder(JwtProperties properties) {
    Objects.requireNonNull(properties.jwksUri(), "parksmart.security.jwt.jwks-uri is required");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(validator(properties));
    return decoder;
  }

  /** Validates expiry/not-before (60s skew), issuer, and audience. */
  public static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
    Objects.requireNonNull(properties.issuer(), "parksmart.security.jwt.issuer is required");
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.issuer()),
        audienceValidator(properties.audience()),
        subjectValidator());
  }

  /** Maps the {@code roles} claim to {@code ROLE_*} authorities; principal name is {@code sub}. */
  public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
    return jwt -> {
      Collection<GrantedAuthority> authorities =
          CurrentAccount.roles(jwt).stream()
              .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
              .toList();
      return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    };
  }

  private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
    return jwt -> {
      List<String> audiences = jwt.getAudience();
      return audiences != null && audiences.contains(audience)
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "Token audience is not " + audience, null));
    };
  }

  private static OAuth2TokenValidator<Jwt> subjectValidator() {
    return jwt -> {
      try {
        java.util.UUID.fromString(jwt.getSubject());
        return OAuth2TokenValidatorResult.success();
      } catch (RuntimeException e) {
        return OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Token subject must be an account ID", null));
      }
    };
  }
}
