package com.parksmart.listing.api;

import com.parksmart.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** Listing ETags are the quoted version number, e.g. {@code "3"}. */
final class ETags {

  private ETags() {}

  static String of(long version) {
    return "\"" + version + "\"";
  }

  /** Parses an If-Match value; anything that is not one of our ETags can never match (412). */
  static long parse(String ifMatch) {
    String value = ifMatch == null ? "" : ifMatch.strip();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED,
          "precondition-failed",
          "Listing changed",
          "If-Match does not match the current ETag");
    }
  }
}
