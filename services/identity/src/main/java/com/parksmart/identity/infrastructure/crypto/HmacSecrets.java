package com.parksmart.identity.infrastructure.crypto;

import com.parksmart.identity.application.IdentityProperties;
import com.parksmart.identity.application.port.Secrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Secrets are hashed with HMAC-SHA256 keyed by the server pepper: a leaked database alone cannot be
 * used to brute-force 6-digit codes offline.
 */
@Component
class HmacSecrets implements Secrets {

  private static final String ALGORITHM = "HmacSHA256";

  private final SecureRandom random = new SecureRandom();
  private final SecretKeySpec key;

  HmacSecrets(IdentityProperties properties) {
    this.key =
        new SecretKeySpec(properties.codePepper().getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  @Override
  public String newSixDigitCode() {
    return String.format("%06d", random.nextInt(1_000_000));
  }

  @Override
  public String newOpaqueToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Override
  public String hash(String secret) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC unavailable", e);
    }
  }
}
