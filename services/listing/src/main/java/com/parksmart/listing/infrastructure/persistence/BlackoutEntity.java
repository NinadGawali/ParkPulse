package com.parksmart.listing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code availability_blackouts}. The generated {@code date_range} column is not mapped. */
@Entity
@Table(name = "availability_blackouts")
class BlackoutEntity {

  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "listing_id", nullable = false, updatable = false)
  ListingEntity listing;

  @Column(name = "start_at", nullable = false)
  Instant startAt;

  @Column(name = "end_at", nullable = false)
  Instant endAt;

  @Column(name = "reason")
  String reason;

  protected BlackoutEntity() {}
}
