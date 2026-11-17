package com.parksmart.identity.application;

/** Access + refresh token pair returned by login and refresh. */
public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {

  @Override
  public String toString() {
    return "Tokens[expiresInSeconds=" + expiresInSeconds + "]";
  }
}
