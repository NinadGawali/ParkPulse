package com.parksmart.listing.infrastructure.persistence;

import com.parksmart.listing.domain.Enums.PhotoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_photos")
class ListingPhotoEntity {

  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "listing_id", nullable = false, updatable = false)
  ListingEntity listing;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  PhotoStatus status;

  @Column(name = "object_key")
  String objectKey;

  @Column(name = "url", length = 1024)
  String url;

  @Column(name = "staging_key")
  String stagingKey;

  @Column(name = "content_type", nullable = false, length = 30, updatable = false)
  String contentType;

  @Column(name = "content_length", nullable = false, updatable = false)
  long contentLength;

  @Column(name = "sort_order", nullable = false)
  int sortOrder;

  @Column(name = "upload_expires_at", nullable = false, updatable = false)
  Instant uploadExpiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  protected ListingPhotoEntity() {}
}
