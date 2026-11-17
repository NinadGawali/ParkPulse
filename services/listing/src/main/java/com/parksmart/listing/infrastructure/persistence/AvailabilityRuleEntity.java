package com.parksmart.listing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "availability_rules")
class AvailabilityRuleEntity {

  @Id UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "listing_id", nullable = false, updatable = false)
  ListingEntity listing;

  /** ISO 1 (Monday) - 7 (Sunday). */
  @Column(name = "day_of_week", nullable = false)
  short dayOfWeek;

  @Column(name = "start_minute", nullable = false)
  short startMinute;

  @Column(name = "end_minute", nullable = false)
  short endMinute;

  protected AvailabilityRuleEntity() {}
}
