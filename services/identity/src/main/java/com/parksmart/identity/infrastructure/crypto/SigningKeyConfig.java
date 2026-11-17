package com.parksmart.identity.infrastructure.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.parksmart.common.security.JwtProperties;
import com.parksmart.common.security.ParkSmartJwt;
import com.parksmart.identity.application.IdentityProperties;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Loads the RS256 signing key from {@code parksmart.identity.jwt.private-key-pem}. Local and test
 * profiles may opt into a throwaway key; every other environment fails to start without one.
 */
@Configuration(proxyBeanMethods = false)
class SigningKeyConfig {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

  @Bean
  RSAKey signingKey(IdentityProperties properties) throws Exception {
    String pem = properties.jwt().privateKeyPem();
    if (pem != null && !pem.isBlank()) {
      return fromPem(pem);
    }
    if (properties.jwt().generateEphemeralKey()) {
      log.warn("No JWT signing key configured; generated an ephemeral key (local/test only)");
      return new RSAKeyGenerator(2048)
          .keyUse(KeyUse.SIGNATURE)
          .keyIDFromThumbprint(true)
          .generate();
    }
    throw new IllegalStateException("parksmart.identity.jwt.private-key-pem must be set");
  }

  /** Identity validates its own tokens with the same rules as every other service. */
  @Bean
  JwtDecoder jwtDecoder(RSAKey signingKey, IdentityProperties properties) throws JOSEException {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        ParkSmartJwt.validator(
            new JwtProperties(properties.issuer(), properties.audience(), null)));
    return decoder;
  }

  static RSAKey fromPem(String pem) throws GeneralSecurityException, JOSEException {
    String body =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replaceAll("\\s", "");
    KeyFactory factory = KeyFactory.getInstance("RSA");
    RSAPrivateCrtKey privateKey =
        (RSAPrivateCrtKey)
            factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    RSAPublicKey publicKey =
        (RSAPublicKey)
            factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    RSAKey key =
        new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build();
    return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
  }
}
