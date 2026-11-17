package com.parksmart.common.security;

/** Claim names and fixed values of ParkSmart access tokens. */
public final class JwtClaims {

  public static final String ROLES = "roles";
  public static final String DEFAULT_AUDIENCE = "parksmart-api";

  private JwtClaims() {}
}
