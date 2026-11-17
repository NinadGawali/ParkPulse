package com.parksmart.profile.domain;

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
            .anyMatch(c ->
                (Character.isISOControl(c) && !(allowNewlines && (c == '\n' || c == '\r')))
                    || invisible(c));
    if (bad) {
      throw new DomainException(
          "invalid-" + field, field + " contains control or invisible characters");
    }
    return trimmed;
  }

  /**
   * Bidi overrides/isolates, zero-width characters and line/paragraph separators: they let text
   * look different from what it is (spoofing in admin screens and emails).
   */
  private static boolean invisible(int c) {
    return (c >= 0x202A && c <= 0x202E)
        || (c >= 0x2066 && c <= 0x2069)
        || (c >= 0x200B && c <= 0x200F)
        || c == 0x2028
        || c == 0x2029
        || c == 0xFEFF;
  }
}
