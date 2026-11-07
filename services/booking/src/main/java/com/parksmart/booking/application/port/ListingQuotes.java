package com.parksmart.booking.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Listing's internal booking quote ({@code GET /internal/v1/listings/{id}/booking-quote}), called
 * with the driver's token. Implementations map failures to API problems: unknown listing 404,
 * Listing unreachable or failing 503 {@code listing-unavailable}.
 */
public interface ListingQuotes {

  Quote quote(UUID listingId, Instant start, Instant end, String driverAccessToken);

  record Quote(
      UUID listingId,
      UUID hostAccountId,
      String status,
      boolean bookable,
      String unbookableReason,
      BigDecimal pricePerHour,
      String currency,
      boolean instantBook,
      String cancellationPolicy,
      int bufferMinutes) {}
}
