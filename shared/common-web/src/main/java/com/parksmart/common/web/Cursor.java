package com.parksmart.common.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor over {@code (sortKey, id)}. Encoded as base64url so clients cannot depend on
 * its contents.
 */
public record Cursor(Instant sortKey, UUID id) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;
  private static final char SEPARATOR = '~';

  /** Far beyond any real timestamp, but well inside what Postgres TIMESTAMPTZ can store. */
  private static final Instant MAX_SORT_KEY = Instant.parse("9999-12-31T23:59:59Z");

  public String encode() {
    String raw = sortKey.toString() + SEPARATOR + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Decodes a cursor; {@code null} or blank means "first page". Invalid input is a 400. */
  public static Cursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      int separator = raw.indexOf(SEPARATOR);
      Instant sortKey = Instant.parse(raw.substring(0, separator));
      if (sortKey.isBefore(Instant.EPOCH) || sortKey.isAfter(MAX_SORT_KEY)) {
        throw new IllegalArgumentException("sort key out of range");
      }
      return new Cursor(sortKey, UUID.fromString(raw.substring(separator + 1)));
    } catch (RuntimeException e) {
      throw ApiException.badRequest("invalid-cursor", "Invalid cursor", "The cursor is malformed");
    }
  }

  /** Clamps a requested page size to 1..MAX_LIMIT (default DEFAULT_LIMIT). */
  public static int limit(Integer requested) {
    if (requested == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, requested));
  }
}
