package com.parksmart.profile.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A user's display profile. 1:1 with an Identity account (logical reference only). */
public final class Profile {

  public static final int DISPLAY_NAME_MAX = 60;
  public static final int BIO_MAX = 500;
  public static final int CITY_MAX = 100;

  private final UUID accountId;
  private String displayName;
  private String bio;
  private String city;
  private ProfilePhoto photo;
  private DriverRating rating;
  private final Instant createdAt;
  private Instant updatedAt;
  private final long version;

  private Profile(
      UUID accountId,
      String displayName,
      String bio,
      String city,
      ProfilePhoto photo,
      DriverRating rating,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.displayName = displayName;
    this.bio = bio;
    this.city = city;
    this.photo = photo;
    this.rating = rating == null ? DriverRating.NONE : rating;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.version = version;
  }

  /** An empty profile for a newly registered account. */
  public static Profile createEmpty(UUID accountId, Instant now) {
    return new Profile(accountId, null, null, null, null, DriverRating.NONE, now, now, -1);
  }

  /** Rebuilds a profile from storage. */
  public static Profile restore(
      UUID accountId,
      String displayName,
      String bio,
      String city,
      ProfilePhoto photo,
      DriverRating rating,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    return new Profile(
        accountId, displayName, bio, city, photo, rating, createdAt, updatedAt, version);
  }

  /**
   * Replaces the editable fields.
   *
   * @return true when a publicly visible field (display name) changed
   */
  public boolean updateDetails(String newDisplayName, String newBio, String newCity, Instant now) {
    String name = Text.clean(newDisplayName, "displayName", 2, DISPLAY_NAME_MAX, true, false);
    String cleanBio = Text.clean(newBio, "bio", 1, BIO_MAX, false, true);
    String cleanCity = Text.clean(newCity, "city", 1, CITY_MAX, false, false);
    boolean publicChange = !name.equals(displayName);
    displayName = name;
    bio = cleanBio;
    city = cleanCity;
    updatedAt = now;
    return publicChange;
  }

  /** Sets a new photo and returns the one it replaces (to delete from storage). */
  public Optional<ProfilePhoto> replacePhoto(ProfilePhoto newPhoto, Instant now) {
    Objects.requireNonNull(newPhoto, "newPhoto");
    ProfilePhoto previous = photo;
    photo = newPhoto;
    updatedAt = now;
    return Optional.ofNullable(previous);
  }

  /** Removes the photo; returns it if there was one. */
  public Optional<ProfilePhoto> removePhoto(Instant now) {
    ProfilePhoto previous = photo;
    if (previous != null) {
      photo = null;
      updatedAt = now;
    }
    return Optional.ofNullable(previous);
  }

  /**
   * Caches a driver rating. Ratings older than the cached one are ignored, so out-of-order events
   * cannot roll the value back.
   *
   * @return true if the cached rating changed
   */
  public boolean applyRating(BigDecimal average, int count, Instant asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (rating.asOf() != null && !asOf.isAfter(rating.asOf())) {
      return false;
    }
    BigDecimal rounded = average == null ? null : average.setScale(2, RoundingMode.HALF_UP);
    rating = new DriverRating(count == 0 ? null : rounded, count, asOf);
    return true;
  }

  public boolean hasPhoto() {
    return photo != null;
  }

  /** True until the profile has been stored once. */
  public boolean isNew() {
    return version < 0;
  }

  public UUID accountId() {
    return accountId;
  }

  public String displayName() {
    return displayName;
  }

  public String bio() {
    return bio;
  }

  public String city() {
    return city;
  }

  public Optional<ProfilePhoto> photo() {
    return Optional.ofNullable(photo);
  }

  public DriverRating rating() {
    return rating;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public long version() {
    return version;
  }
}
