package com.parksmart.adminbff.application.port;

import com.parksmart.adminbff.domain.Facts;
import com.parksmart.common.web.CursorPage;
import java.util.Optional;
import java.util.UUID;

/**
 * The owning services' REST APIs, as the BFF uses them. Implementations forward the admin's token,
 * time out quickly and throw {@link com.parksmart.common.web.ApiException}: downstream 4xx problems
 * pass through, unreachable/slow/5xx become 503 {@code <service>-unavailable}. {@code find*}
 * methods return empty on 404 instead.
 */
public final class Downstream {

  private Downstream() {}

  /** Listing service: moderation endpoints and the admin view of one listing. */
  public interface Listings {
    CursorPage<Facts.ListingRow> pendingReview(AdminCall call, String cursor, int limit);

    Optional<Facts.Listing> find(AdminCall call, UUID listingId);

    Facts.Listing approve(AdminCall call, UUID listingId, String reason);

    Facts.Listing reject(AdminCall call, UUID listingId, String reason);
  }

  /** Profile service: public profiles only. */
  public interface Profiles {
    Optional<Facts.Party> find(AdminCall call, UUID accountId);
  }

  /** Booking service. */
  public interface Bookings {
    Optional<Facts.Booking> find(AdminCall call, UUID bookingId);
  }

  /** Payment service: intent lookup and the admin refund. */
  public interface Payments {
    Optional<Facts.Payment> findForBooking(AdminCall call, UUID bookingId);

    Facts.Payment refund(
        AdminCall call, UUID intentId, String idempotencyKey, String amount, String reason);
  }

  /** Review service: moderation. */
  public interface Reviews {
    Facts.Review hide(AdminCall call, UUID reviewId, String reason);
  }
}
