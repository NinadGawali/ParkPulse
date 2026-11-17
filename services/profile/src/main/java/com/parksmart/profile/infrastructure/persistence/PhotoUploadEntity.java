package com.parksmart.profile.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "profile_photo_uploads")
class PhotoUploadEntity implements Persistable<UUID> {

  @Id UUID id;

  @Column(name = "account_id", nullable = false, updatable = false)
  UUID accountId;

  @Column(name = "object_key", nullable = false, updatable = false)
  String objectKey;

  @Column(name = "content_type", nullable = false, updatable = false, length = 30)
  String contentType;

  @Column(name = "content_length", nullable = false, updatable = false)
  long contentLength;

  @Column(name = "expires_at", nullable = false, updatable = false)
  Instant expiresAt;

  @Column(name = "confirmed_at")
  Instant confirmedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Transient boolean isNew;

  protected PhotoUploadEntity() {}

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }
}
