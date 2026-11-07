package com.parksmart.listing.application.port;

import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Listing;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository {

  /** Any listing, archived ones included. */
  Optional<Listing> findById(UUID id);

  /**
   * Inserts or updates (with its photos, rules, blackouts and amenities) and flushes. Every save
   * bumps the version, which is the listing's ETag.
   *
   * @return the new version
   */
  long save(Listing listing);

  /** A host's non-archived listings, newest first (keyset after {@code (createdAt, id)}). */
  List<Listing> pageByHost(
      UUID hostAccountId, ListingStatus status, Instant afterCreatedAt, UUID afterId, int limit);

  /** Listings in a status, oldest change first (moderation queue; keyset on updatedAt, id). */
  List<Listing> pageByStatus(ListingStatus status, Instant afterUpdatedAt, UUID afterId, int limit);

  List<Listing> findByHostAndStatus(UUID hostAccountId, ListingStatus status);
}
