package com.parksmart.identity.application.port;

public interface PasswordHasher {

  String hash(String rawPassword);

  boolean matches(String rawPassword, String hash);

  /** Burns the same time as a real check; used when the account does not exist. */
  void simulateCheck(String rawPassword);
}
