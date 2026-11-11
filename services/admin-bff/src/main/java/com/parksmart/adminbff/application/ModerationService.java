package com.parksmart.adminbff.application;

import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import com.parksmart.adminbff.domain.Lookup;
import com.parksmart.adminbff.domain.ModerationQueueItem;
import com.parksmart.common.web.CursorPage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Listing and review moderation. Every method requires ADMIN. */
@Service
public class ModerationService {

  private final Downstream.Listings listings;
  private final Downstream.Profiles profiles;
  private final Downstream.Reviews reviews;
  private final ParallelLookups lookups;

  ModerationService(
      Downstream.Listings listings,
      Downstream.Profiles profiles,
      Downstream.Reviews reviews,
      ParallelLookups lookups) {
    this.listings = listings;
    this.profiles = profiles;
    this.reviews = reviews;
    this.lookups = lookups;
  }

  /**
   * One page of PENDING_REVIEW listings (Listing's order and cursor), each with its details and the
   * host's public profile. Details are fetched in parallel; each host's profile only once.
   */
  public CursorPage<ModerationQueueItem> queue(String cursor, int limit) {
    AdminCall call = Admins.require();
    CursorPage<Facts.ListingRow> page = listings.pendingReview(call, cursor, limit);
    Map<UUID, CompletableFuture<Lookup<Facts.Party>>> hosts = new ConcurrentHashMap<>();
    List<CompletableFuture<ModerationQueueItem>> items =
        page.items().stream()
            .map(row ->
                lookups
                    .lookup("listing", () -> listings.find(call, row.listingId()))
                    .thenCompose(details -> {
                      Facts.Listing listing = details.orNull();
                      CompletableFuture<Lookup<Facts.Party>> host =
                          listing == null
                              ? CompletableFuture.completedFuture(Lookup.absent())
                              : hosts.computeIfAbsent(
                                  listing.hostAccountId(),
                                  id -> lookups.lookup("profile", () -> profiles.find(call, id)));
                      return host.thenApply(h -> ModerationQueueItem.compose(row, details, h));
                    }))
            .toList();
    return new CursorPage<>(items.stream().map(ParallelLookups::join).toList(), page.nextCursor());
  }

  public Facts.Listing approve(UUID listingId, String reason) {
    return listings.approve(Admins.require(), listingId, reason);
  }

  public Facts.Listing reject(UUID listingId, String reason) {
    return listings.reject(Admins.require(), listingId, reason);
  }

  public Facts.Review hideReview(UUID reviewId, String reason) {
    return reviews.hide(Admins.require(), reviewId, reason);
  }
}
