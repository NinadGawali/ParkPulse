package com.parksmart.identity.application.port;

/** Generates and hashes one-time secrets (verification codes, reset and refresh tokens). */
public interface Secrets {

  /** Six random digits. */
  String newSixDigitCode();

  /** 256-bit random value, base64url without padding. */
  String newOpaqueToken();

  /** Keyed hash (HMAC-SHA256 with the server pepper), base64url. Deterministic for lookups. */
  String hash(String secret);
}
