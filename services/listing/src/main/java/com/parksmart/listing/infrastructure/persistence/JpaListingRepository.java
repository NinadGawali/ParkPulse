package com.parksmart.listing.infrastructure.persistence;

import com.parksmart.listing.application.port.ListingRepository;
import com.parksmart.listing.domain.AvailabilityRule;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Listing;
import com.parksmart.listing.domain.ListingPhoto;
import com.parksmart.listing.domain.PhotoContentType;
import com.parksmart.listing.domain.Values.Blackout;
import com.parksmart.listing.domain.Values.GeoPoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/**
 * Maps the {@link Listing} aggregate to {@link ListingEntity} and its child tables. Children are
 * synchronized by ID; every save force-increments the version right away (the new ETag is known
 * before commit), so any change to the aggregate (photos and rules included) changes the ETag and
 * loses an optimistic-lock race cleanly.
 */
@Repository
class JpaListingRepository implements ListingRepository {

  private final ListingJpaRepository jpa;
  private final EntityManager em;

  JpaListingRepository(ListingJpaRepository jpa, EntityManager em) {
    this.jpa = jpa;
    this.em = em;
  }

  @Override
  public Optional<Listing> findById(UUID id) {
    return jpa.findById(id).map(JpaListingRepository::toDomain);
  }

  @Override
  public long save(Listing listing) {
    ListingEntity e;
    if (listing.isNew()) {
      e = new ListingEntity(listing.id());
      e.hostAccountId = listing.hostAccountId();
      e.createdAt = listing.createdAt();
    } else {
      e = jpa.findById(listing.id()).orElseThrow();
      em.lock(e, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
    e.title = listing.title();
    e.description = listing.description();
    e.address = listing.address();
    e.latitude = listing.location().lat();
    e.longitude = listing.location().lng();
    e.timeZone = listing.timeZone().getId();
    e.pricePerHour = listing.pricePerHour();
    e.currency = listing.currency();
    e.maxVehicleType = listing.maxVehicleType();
    e.instantBook = listing.instantBook();
    e.cancellationPolicy = listing.cancellationPolicy();
    e.bufferMinutes = (short) listing.bufferMinutes();
    e.status = listing.status();
    e.updatedAt = listing.updatedAt();
    e.deletedAt = listing.deletedAt();
    if (!e.amenities.equals(listing.amenities())) {
      e.amenities.clear();
      e.amenities.addAll(listing.amenities());
    }
    syncPhotos(e, listing.photos());
    syncRules(e, listing.rules());
    syncBlackouts(e, listing.blackouts());
    if (listing.isNew()) {
      jpa.save(e);
    }
    jpa.flush();
    return e.version;
  }

  private static void syncPhotos(ListingEntity e, List<ListingPhoto> photos) {
    Map<UUID, ListingPhoto> wanted =
        photos.stream().collect(Collectors.toMap(ListingPhoto::id, p -> p));
    e.photos.removeIf(p -> !wanted.containsKey(p.id));
    Map<UUID, ListingPhotoEntity> existing = new HashMap<>();
    e.photos.forEach(p -> existing.put(p.id, p));
    for (ListingPhoto p : photos) {
      ListingPhotoEntity pe = existing.get(p.id());
      if (pe == null) {
        pe = new ListingPhotoEntity();
        pe.id = p.id();
        pe.listing = e;
        pe.contentType = p.contentType().mediaType();
        pe.contentLength = p.contentLength();
        pe.uploadExpiresAt = p.uploadExpiresAt();
        pe.createdAt = p.createdAt();
        e.photos.add(pe);
      }
      pe.status = p.status();
      pe.objectKey = p.objectKey();
      pe.url = p.url();
      pe.stagingKey = p.stagingKey();
      pe.sortOrder = p.sortOrder();
    }
  }

  /** Rules have no identity of their own: replace them when they differ. */
  private static void syncRules(ListingEntity e, List<AvailabilityRule> rules) {
    List<AvailabilityRule> current =
        e.rules.stream().map(JpaListingRepository::toRule).sorted(RULE_ORDER).toList();
    if (current.equals(rules.stream().sorted(RULE_ORDER).toList())) {
      return;
    }
    e.rules.clear();
    for (AvailabilityRule r : rules) {
      AvailabilityRuleEntity re = new AvailabilityRuleEntity();
      re.id = UUID.randomUUID();
      re.listing = e;
      re.dayOfWeek = (short) r.day().getValue();
      re.startMinute = (short) r.startMinute();
      re.endMinute = (short) r.endMinute();
      e.rules.add(re);
    }
  }

  private static final java.util.Comparator<AvailabilityRule> RULE_ORDER =
      java.util.Comparator.comparing(AvailabilityRule::day)
          .thenComparingInt(AvailabilityRule::startMinute);

  private static void syncBlackouts(ListingEntity e, List<Blackout> blackouts) {
    Set<UUID> wanted = blackouts.stream().map(Blackout::id).collect(Collectors.toSet());
    e.blackouts.removeIf(b -> !wanted.contains(b.id));
    Set<UUID> existing = e.blackouts.stream().map(b -> b.id).collect(Collectors.toSet());
    for (Blackout b : blackouts) {
      if (!existing.contains(b.id())) {
        BlackoutEntity be = new BlackoutEntity();
        be.id = b.id();
        be.listing = e;
        be.startAt = b.start();
        be.endAt = b.end();
        be.reason = b.reason();
        e.blackouts.add(be);
      }
    }
  }

  @Override
  public List<Listing> pageByHost(
      UUID host, ListingStatus status, Instant after, UUID afterId, int limit) {
    List<ListingEntity> rows =
        after == null
            ? jpa.firstPageByHost(host, status, Limit.of(limit))
            : jpa.nextPageByHost(host, status, after, afterId, Limit.of(limit));
    return rows.stream().map(JpaListingRepository::toDomain).toList();
  }

  @Override
  public List<Listing> pageByStatus(ListingStatus status, Instant after, UUID afterId, int limit) {
    List<ListingEntity> rows =
        after == null
            ? jpa.firstPageByStatus(status, Limit.of(limit))
            : jpa.nextPageByStatus(status, after, afterId, Limit.of(limit));
    return rows.stream().map(JpaListingRepository::toDomain).toList();
  }

  @Override
  public List<Listing> findByHostAndStatus(UUID host, ListingStatus status) {
    return jpa.findByHostAccountIdAndStatus(host, status).stream()
        .map(JpaListingRepository::toDomain)
        .toList();
  }

  private static AvailabilityRule toRule(AvailabilityRuleEntity r) {
    return new AvailabilityRule(DayOfWeek.of(r.dayOfWeek), r.startMinute, r.endMinute);
  }

  private static Listing toDomain(ListingEntity e) {
    return Listing.restore(
        e.id,
        e.hostAccountId,
        e.title,
        e.description,
        e.address,
        new GeoPoint(e.latitude, e.longitude),
        ZoneId.of(e.timeZone),
        e.pricePerHour,
        e.currency,
        e.maxVehicleType,
        e.amenities,
        e.instantBook,
        e.cancellationPolicy,
        e.bufferMinutes,
        e.status,
        e.createdAt,
        e.updatedAt,
        e.deletedAt,
        e.version,
        e.photos.stream()
            .map(p ->
                new ListingPhoto(
                    p.id,
                    p.status,
                    p.objectKey,
                    p.url,
                    p.stagingKey,
                    PhotoContentType.fromMediaType(p.contentType).orElseThrow(),
                    p.contentLength,
                    p.sortOrder,
                    p.uploadExpiresAt,
                    p.createdAt))
            .toList(),
        e.rules.stream().map(JpaListingRepository::toRule).toList(),
        e.blackouts.stream().map(b -> new Blackout(b.id, b.startAt, b.endAt, b.reason)).toList());
  }
}
