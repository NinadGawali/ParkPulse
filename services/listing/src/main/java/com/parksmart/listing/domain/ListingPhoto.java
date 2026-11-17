package com.parksmart.listing.domain;

import com.parksmart.listing.domain.Enums.PhotoStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A listing photo. Clients upload to a private staging key with a presigned URL; confirm copies the
 * bytes to {@code objectKey} (public, server-owned) and marks the photo READY.
 */
public final class ListingPhoto {

  private final UUID id;
  private PhotoStatus status;
  private String objectKey;
  private String url;
  private String stagingKey;
  private final PhotoContentType contentType;
  private final long contentLength;
  private int sortOrder;
  private final Instant uploadExpiresAt;
  private final Instant createdAt;

  public ListingPhoto(
      UUID id,
      PhotoStatus status,
      String objectKey,
      String url,
      String stagingKey,
      PhotoContentType contentType,
      long contentLength,
      int sortOrder,
      Instant uploadExpiresAt,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.status = Objects.requireNonNull(status, "status");
    this.objectKey = objectKey;
    this.url = url;
    this.stagingKey = stagingKey;
    this.contentType = Objects.requireNonNull(contentType, "contentType");
    this.contentLength = contentLength;
    this.sortOrder = sortOrder;
    this.uploadExpiresAt = Objects.requireNonNull(uploadExpiresAt, "uploadExpiresAt");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  void markReady(String publicKey, String publicUrl, int order) {
    status = PhotoStatus.READY;
    objectKey = publicKey;
    url = publicUrl;
    sortOrder = order;
  }

  void setSortOrder(int order) {
    sortOrder = order;
  }

  /** The staging object has been deleted from storage. */
  public void clearStagingKey() {
    stagingKey = null;
  }

  public boolean isReady() {
    return status == PhotoStatus.READY;
  }

  public UUID id() {
    return id;
  }

  public PhotoStatus status() {
    return status;
  }

  public String objectKey() {
    return objectKey;
  }

  public String url() {
    return url;
  }

  public String stagingKey() {
    return stagingKey;
  }

  public PhotoContentType contentType() {
    return contentType;
  }

  public long contentLength() {
    return contentLength;
  }

  public int sortOrder() {
    return sortOrder;
  }

  public Instant uploadExpiresAt() {
    return uploadExpiresAt;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
