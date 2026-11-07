package com.parksmart.listing.domain;

/** Normalizes user-entered text: trims, rejects control characters, enforces length. */
final class Text {

  private Text() {}

  /** Returns the trimmed value, or null for a null or blank optional value. */
  static String clean(
      String value, String field, int min, int max, boolean required, boolean allowNewlines) {
    String trimmed = value == null ? null : value.strip();
    if (trimmed == null || trimmed.isEmpty()) {
      if (required) {
        throw new DomainException("invalid-" + field, field + " is required");
      }
      return null;
    }
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < min || length > max) {
      throw new DomainException(
          "invalid-" + field, field + " must be " + min + "-" + max + " characters");
    }
    boolean bad =
        trimmed
            .codePoints()
            .anyMatch(
                c -> Character.isISOControl(c) && !(allowNewlines && (c == '\n' || c == '\r')));
    if (bad) {
      throw new DomainException("invalid-" + field, field + " contains control characters");
    }
    return trimmed;
  }
}
