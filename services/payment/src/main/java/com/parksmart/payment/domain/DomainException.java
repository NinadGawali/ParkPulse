package com.parksmart.payment.domain;

/** A business rule was violated. {@code code} is stable and maps to an API problem type. */
public class DomainException extends RuntimeException {

  private final String code;

  public DomainException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
