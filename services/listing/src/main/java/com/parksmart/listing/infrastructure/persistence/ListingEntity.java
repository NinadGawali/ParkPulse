package com.parksmart.listing.infrastructure.persistence;

import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.VehicleType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code parking_listings}. The generated {@code location} geography column is not mapped. */
@Entity
@Table(name = "parking_listings")
class ListingEntity {

  @Id UUID id;

  @Column(name = "host_account_id", nullable = false, updatable = false)
  UUID hostAccountId;

  @Column(name = "title", nullable = false)
  String title;

  @Column(name = "description", columnDefinition = "text")
  String description;

  @Column(name = "address", nullable = false, length = 512)
  String address;

  @Column(name = "latitude", nullable = false)
  double latitude;

  @Column(name = "longitude", nullable = false)
  double longitude;

  @Column(name = "time_zone", nullable = false, length = 64)
  String timeZone;

  @Column(name = "price_per_hour", nullable = false, precision = 8, scale = 2)
  BigDecimal pricePerHour;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "max_vehicle_type", nullable = false, length = 20)
  VehicleType maxVehicleType;

  @Column(name = "instant_book", nullable = false)
  boolean instantBook;

  @Enumerated(EnumType.STRING)
  @Column(name = "cancellation_policy", nullable = false, length = 20)
  CancellationPolicy cancellationPolicy;

  @Column(name = "buffer_minutes", nullable = false)
  short bufferMinutes;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  ListingStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "deleted_at")
  Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  Long version;

  @ElementCollection
  @CollectionTable(name = "listing_amenities", joinColumns = @JoinColumn(name = "listing_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "amenity", length = 30)
  Set<Amenity> amenities = new HashSet<>();

  @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
  List<ListingPhotoEntity> photos = new ArrayList<>();

  @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
  List<AvailabilityRuleEntity> rules = new ArrayList<>();

  @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true)
  List<BlackoutEntity> blackouts = new ArrayList<>();

  protected ListingEntity() {}

  ListingEntity(UUID id) {
    this.id = id;
  }
}
