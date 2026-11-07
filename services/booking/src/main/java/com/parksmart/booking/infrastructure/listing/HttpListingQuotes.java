package com.parksmart.booking.infrastructure.listing;

import com.parksmart.booking.application.port.ListingQuotes;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CorrelationId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls Listing's internal booking quote with the driver's token (vertical-slice plan section 4): 2
 * s timeouts, one retry on a connection error, Listing down or failing = 503 {@code
 * listing-unavailable}.
 */
@Component
class HttpListingQuotes implements ListingQuotes {

  private static final Logger log = LoggerFactory.getLogger(HttpListingQuotes.class);

  private final RestClient client;

  HttpListingQuotes(
      RestClient.Builder builder,
      @Value("${parksmart.listing.base-url}") String baseUrl,
      @Value("${parksmart.listing.timeout:2s}") Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** Listing's BookingQuote JSON (money as a string). */
  record QuoteBody(
      UUID listingId,
      UUID hostAccountId,
      String status,
      boolean bookable,
      String unbookableReason,
      String pricePerHour,
      String currency,
      boolean instantBook,
      String cancellationPolicy,
      int bufferMinutes) {}

  @Override
  public Quote quote(UUID listingId, Instant start, Instant end, String driverAccessToken) {
    try {
      return fetch(listingId, start, end, driverAccessToken);
    } catch (ResourceAccessException first) {
      log.warn("Listing quote failed ({}); retrying once", first.getMessage());
      try {
        return fetch(listingId, start, end, driverAccessToken);
      } catch (ResourceAccessException second) {
        throw unavailable();
      }
    }
  }

  private Quote fetch(UUID listingId, Instant start, Instant end, String token) {
    QuoteBody body;
    try {
      body =
          client
              .get()
              .uri(
                  "/internal/v1/listings/{id}/booking-quote?start={start}&end={end}",
                  listingId,
                  start,
                  end)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .header(CorrelationId.HEADER, CorrelationId.currentOrNew())
              .retrieve()
              .body(QuoteBody.class);
    } catch (RestClientResponseException e) {
      HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
      if (status == HttpStatus.NOT_FOUND) {
        throw ApiException.notFound("Listing");
      }
      if (status == HttpStatus.BAD_REQUEST) {
        throw ApiException.badRequest(
            "invalid-booking-window", "Invalid booking window", "Listing rejected the window");
      }
      if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
        throw ApiException.forbidden("Only drivers can book");
      }
      log.warn("Listing quote returned {}", e.getStatusCode());
      throw unavailable();
    }
    if (body == null) {
      throw unavailable();
    }
    return new Quote(
        body.listingId(),
        body.hostAccountId(),
        body.status(),
        body.bookable(),
        body.unbookableReason(),
        new BigDecimal(body.pricePerHour()),
        body.currency(),
        body.instantBook(),
        body.cancellationPolicy(),
        body.bufferMinutes());
  }

  private static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "listing-unavailable",
        "Listing unavailable",
        "The listing service is not reachable; try again shortly");
  }
}
