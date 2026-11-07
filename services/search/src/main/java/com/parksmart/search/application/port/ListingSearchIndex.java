package com.parksmart.search.application.port;

import com.parksmart.search.domain.Availability.Rule;
import com.parksmart.search.domain.SearchQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The search index (PostGIS now, OpenSearch later). Writes come only from events and apply only
 * when newer than what the index holds.
 */
public interface ListingSearchIndex {

  record Indexed(
      UUID listingId,
      String title,
      String address,
      double lat,
      double lng,
      String timeZone,
      BigDecimal pricePerHour,
      String currency,
      String maxVehicleType,
      List<String> amenities,
      boolean instantBook,
      String cancellationPolicy,
      int bufferMinutes,
      String thumbnailUrl,
      BigDecimal ratingAvg,
      int ratingCount,
      Instant lastEventAt,
      Double distanceKm) {}

  /** Keyset position: the last row a page looked at. */
  record After(double distanceKm, BigDecimal price, UUID listingId) {}

  /**
   * Visible listings matching every filter except the weekly rules (checked by the caller), in sort
   * order after {@code after}.
   */
  List<Indexed> candidates(SearchQuery query, After after, int limit);

  Map<UUID, List<Rule>> rules(Collection<UUID> listingIds);

  Optional<Indexed> findVisible(UUID listingId);

  // ---------------------------------------------------------------- projection writes

  record Snapshot(
      UUID listingId,
      UUID hostAccountId,
      String status,
      String title,
      String address,
      double lat,
      double lng,
      String timeZone,
      BigDecimal pricePerHour,
      String currency,
      String maxVehicleType,
      List<String> amenities,
      boolean instantBook,
      String cancellationPolicy,
      int bufferMinutes,
      String thumbnailUrl,
      List<Rule> rules,
      List<Window> blackouts) {}

  record Window(Instant start, Instant end) {}

  /** Full listing state (Published/Updated). Returns false if an equal or newer one was applied. */
  boolean applySnapshot(Snapshot snapshot, Instant at, Instant now);

  void applyAvailability(
      UUID listingId, List<Rule> rules, List<Window> blackouts, Instant at, Instant now);

  void hide(UUID listingId, Instant at, Instant now);

  void rating(UUID listingId, BigDecimal avg, int count, Instant at, Instant now);

  /** A booking holds {@code [start, blockedUntil)}; ignored once the booking has ended. */
  void busy(UUID bookingId, UUID listingId, Instant start, Instant blockedUntil, Instant now);

  void free(UUID bookingId, Instant now);
}
