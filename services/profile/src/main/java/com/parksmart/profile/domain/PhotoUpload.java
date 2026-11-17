package com.parksmart.profile.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A presigned upload slot for a profile photo, confirmed once the bytes are in storage. */
public final class PhotoUpload {

  public static final long MAX_BYTES = 5L * 1024 * 1024;
  public static final int MAX_PENDING = 5;

  private final UUID id;
  private final UUID accountId;
  private final String objectKey;
  private final PhotoContentType contentType;
  private final long contentLength;
  private final Instant expiresAt;
  private Instant confirmedAt;

  private PhotoUpload(
      UUID id,
      UUID accountId,
      String objectKey,
      PhotoContentType contentType,
      long contentLength,
      Instant expiresAt,
      Instant confirmedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
    this.contentType = Objects.requireNonNull(contentType, "contentType");
    this.contentLength = contentLength;
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.confirmedAt = confirmedAt;
  }

  /**
   * Object keys are server-chosen, so a client can never write outside its own prefix. The client
   * uploads to a private staging key; confirm copies it to {@link #photoKey()}, which no presigned
   * URL can write, and validates the copy.
   */
  public static PhotoUpload start(
      UUID id,
      UUID accountId,
      PhotoContentType type,
      long contentLength,
      Instant now,
      Duration ttl) {
    if (contentLength < 1 || contentLength > MAX_BYTES) {
      throw new DomainException("invalid-contentLength", "contentLength must be 1-" + MAX_BYTES);
    }
    String key = "uploads/" + accountId + "/" + id;
    return new PhotoUpload(id, accountId, key, type, contentLength, now.plus(ttl), null);
  }

  public static PhotoUpload restore(
      UUID id,
      UUID accountId,
      String objectKey,
      PhotoContentType contentType,
      long contentLength,
      Instant expiresAt,
      Instant confirmedAt) {
    return new PhotoUpload(
        id, accountId, objectKey, contentType, contentLength, expiresAt, confirmedAt);
  }

  /** Marks the upload used. The caller has already checked the stored object. */
  public void confirm(Instant now) {
    if (confirmedAt != null) {
      throw new DomainException("upload-confirmed", "Upload was already confirmed");
    }
    if (!now.isBefore(expiresAt)) {
      throw new DomainException("upload-expired", "Upload slot has expired; request a new one");
    }
    confirmedAt = now;
  }

  /** The public key the confirmed photo is served from. */
  public String photoKey() {
    return "profiles/" + accountId + "/photo-" + id;
  }

  public boolean isOwnedBy(UUID account) {
    return accountId.equals(account);
  }

  public UUID id() {
    return id;
  }

  public UUID accountId() {
    return accountId;
  }

  public String objectKey() {
    return objectKey;
  }

  public PhotoContentType contentType() {
    return contentType;
  }

  public long contentLength() {
    return contentLength;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant confirmedAt() {
    return confirmedAt;
  }
}
