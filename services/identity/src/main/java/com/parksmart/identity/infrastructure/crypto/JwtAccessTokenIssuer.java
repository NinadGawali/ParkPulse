package com.parksmart.identity.infrastructure.crypto;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.parksmart.common.security.JwtClaims;
import com.parksmart.identity.application.IdentityProperties;
import com.parksmart.identity.application.port.AccessTokenIssuer;
import com.parksmart.identity.domain.Account;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

  private final JwtEncoder encoder;
  private final String keyId;
  private final IdentityProperties properties;
  private final Clock clock;

  JwtAccessTokenIssuer(RSAKey signingKey, IdentityProperties properties, Clock clock) {
    this.encoder =
        new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
    this.keyId = signingKey.getKeyID();
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public IssuedAccessToken issue(Account account) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.accessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(account.id().toString())
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim(JwtClaims.ROLES, account.roles().stream().map(Enum::name).sorted().toList())
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedAccessToken(token, properties.accessTokenTtl().toSeconds());
  }
}
