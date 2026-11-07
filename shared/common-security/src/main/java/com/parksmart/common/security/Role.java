package com.parksmart.common.security;

/** Roles carried in the JWT {@code roles} claim. */
public enum Role {
  DRIVER,
  HOST,
  ADMIN;

  /** Spring Security authority name, e.g. {@code ROLE_HOST}. */
  public String authority() {
    return "ROLE_" + name();
  }
}
