package com.parksmart.identity.domain;

/** Minimum password rules: 10–128 characters with at least one letter and one digit. */
public final class PasswordPolicy {

  public static final int MIN_LENGTH = 10;
  public static final int MAX_LENGTH = 128;

  private PasswordPolicy() {}

  public static void check(String password) {
    if (password == null
        || password.length() < MIN_LENGTH
        || password.length() > MAX_LENGTH
        || password.chars().noneMatch(Character::isLetter)
        || password.chars().noneMatch(Character::isDigit)) {
      throw new DomainException(
          "weak-password", "Password must be 10-128 characters with a letter and a digit");
    }
  }
}
