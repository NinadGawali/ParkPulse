package com.parksmart.profile.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profiles")
class ProfileEntity {

  @Id
  @Column(name = "account_id")
  UUID accountId;

  @Column(name = "display_name", length = 150)
  String displayName;

  @Column(name = "bio", columnDefinition = "text")
  String bio;

  @Column(name = "city", length = 150)
  String city;

  @Column(name = "photo_url", length = 1024)
  String photoUrl;

  @Column(name = "photo_object_key")
  String photoObjectKey;

  @Column(name = "rating_avg", precision = 3, scale = 2)
  BigDecimal ratingAvg;

  @Column(name = "rating_count", nullable = false)
  int ratingCount;

  @Column(name = "rating_updated_at")
  Instant ratingUpdatedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  Long version;

  protected ProfileEntity() {}

  ProfileEntity(UUID accountId) {
    this.accountId = accountId;
  }
}
