package com.parksmart.listing.domain;

import java.util.Arrays;
import java.util.Optional;

/** Accepted image types, with the file signature each must start with. */
public enum PhotoContentType {
  JPEG("image/jpeg"),
  PNG("image/png"),
  WEBP("image/webp");

  /** Bytes needed to check any signature. */
  public static final int SIGNATURE_BYTES = 12;

  private final String mediaType;

  PhotoContentType(String mediaType) {
    this.mediaType = mediaType;
  }

  public String mediaType() {
    return mediaType;
  }

  public static Optional<PhotoContentType> fromMediaType(String mediaType) {
    return Arrays.stream(values()).filter(t -> t.mediaType.equalsIgnoreCase(mediaType)).findFirst();
  }

  /** True when the first bytes of the file match this type's signature. */
  public boolean matchesSignature(byte[] head) {
    return switch (this) {
      case JPEG -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
      case PNG -> startsWith(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
      case WEBP ->
          startsWith(head, 0, 0x52, 0x49, 0x46, 0x46)
              && startsWith(head, 8, 0x57, 0x45, 0x42, 0x50);
    };
  }

  private static boolean startsWith(byte[] data, int offset, int... expected) {
    if (data == null || data.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((data[offset + i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }
}
