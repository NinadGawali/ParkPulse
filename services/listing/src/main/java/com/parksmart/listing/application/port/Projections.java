package com.parksmart.listing.application.port;

import com.parksmart.listing.domain.Values.HostEligibility;
import java.time.Instant;
import java.util.UUID;

/** Local read models built from other services' events. */
public final class Projections {

  private Projections() {}

  /** host_eligibility: each field only moves to a newer event's value. */
  public interface HostEligibilityProjection {

    /** {@link HostEligibility#UNKNOWN} when no event has been seen for the host. */
    HostEligibility find(UUID hostAccountId);

    /**
     * Like {@link #find} but holds a shared lock on the host's row until the transaction ends, so a
     * concurrent suspension waits for (and then sees) the listing this call publishes.
     */
    HostEligibility findForPublish(UUID hostAccountId, Instant now);

    void phoneVerified(UUID hostAccountId, boolean verified, Instant at);

    void profilePhoto(UUID hostAccountId, boolean hasPhoto, Instant at);

    void suspended(UUID hostAccountId, Instant at);
  }

  /** listing_active_bookings: bookings that still block a listing from being archived. */
  public interface ActiveBookingsProjection {

    /** Records or extends an active booking; ignored once the booking has ended. */
    void active(UUID bookingId, UUID listingId, Instant blockedUntil, Instant at);

    /** The booking no longer blocks the listing (cancelled, completed, expired, ...). */
    void ended(UUID bookingId, Instant at);

    /** True if an active booking still blocks the listing at {@code now} or later. */
    boolean hasBlocking(UUID listingId, Instant now);
  }

  public interface ModerationLog {

    void record(UUID listingId, UUID adminId, String decision, String reason, Instant at);

    /** The reason of the most recent rejection, if any. */
    java.util.Optional<String> latestRejectionReason(UUID listingId);
  }

  /** Housekeeping for photo uploads (outside the listing aggregate). */
  public interface PhotoUploads {

    record StaleUpload(UUID photoId, String stagingKey) {}

    /** PENDING_UPLOAD photos whose URL expired before {@code cutoff}. */
    java.util.List<StaleUpload> expiredPending(Instant cutoff, int limit);

    /** Confirmed photos whose staging object may still exist (URL expired before cutoff). */
    java.util.List<StaleUpload> staleStaging(Instant cutoff, int limit);

    void deletePending(UUID photoId);

    void clearStaging(UUID photoId);
  }
}
