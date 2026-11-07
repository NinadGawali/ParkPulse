package com.parksmart.identity.infrastructure.crypto;

import com.parksmart.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** argon2id with Spring Security's recommended parameters. */
@Component
class Argon2PasswordHasher implements PasswordHasher {

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final String dummyHash = encoder.encode("timing-equaliser-not-a-real-password-1");

  @Override
  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, String hash) {
    return encoder.matches(rawPassword, hash);
  }

  @Override
  public void simulateCheck(String rawPassword) {
    encoder.matches(rawPassword, dummyHash);
  }
}
