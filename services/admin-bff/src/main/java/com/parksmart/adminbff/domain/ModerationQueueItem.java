package com.parksmart.adminbff.domain;

import com.parksmart.adminbff.domain.Facts.Listing;
import com.parksmart.adminbff.domain.Facts.ListingRow;
import com.parksmart.adminbff.domain.Facts.Party;
import java.util.Objects;

/**
 * One entry of the moderation queue: Listing's row, the listing details (address, photo and rule
 * counts, host) and the host's public profile. Details or host may be missing; {@code partial} says
 * whether that is because a lookup failed.
 */
public record ModerationQueueItem(ListingRow row, Listing details, Party host, boolean partial) {

  public ModerationQueueItem {
    Objects.requireNonNull(row, "row");
  }

  /**
   * Composes the item. Details of another listing, or a profile of someone other than the listing's
   * host, count as failed lookups.
   */
  public static ModerationQueueItem compose(
      ListingRow row, Lookup<Listing> details, Lookup<Party> host) {
    Lookup<Listing> checkedDetails =
        details.requireThat(d -> d.listingId().equals(row.listingId()));
    Listing listing = checkedDetails.orNull();
    Lookup<Party> checkedHost =
        listing == null
            ? Lookup.absent()
            : host.requireThat(p -> p.accountId().equals(listing.hostAccountId()));
    return new ModerationQueueItem(
        row,
        listing,
        checkedHost.orNull(),
        checkedDetails.isFailed() || (listing != null && checkedHost.isFailed()));
  }
}
