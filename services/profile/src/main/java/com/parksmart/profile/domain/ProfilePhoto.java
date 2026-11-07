package com.parksmart.profile.domain;

import java.util.Objects;

/** The stored profile photo: object-storage key and the public URL it is served from. */
public record ProfilePhoto(String objectKey, String url) {

  public ProfilePhoto {
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(url, "url");
  }
}
