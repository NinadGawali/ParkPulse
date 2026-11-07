package com.parksmart.booking.infrastructure.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parksmart.booking.application.port.ListingQuotes.Quote;
import com.parksmart.common.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The real HTTP client against a stub Listing: parsing, token forwarding, error mapping. */
class HttpListingQuotesIT {

  static HttpServer server;
  static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();
  static final Map<String, String> LAST_AUTH = new ConcurrentHashMap<>();
  static final AtomicInteger SLOW_CALLS = new AtomicInteger();
  static final UUID HOST = UUID.randomUUID();

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/internal/v1/listings/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String id = path.split("/")[4];
          LAST_AUTH.put(id, exchange.getRequestHeaders().getFirst("Authorization"));
          if (id.equals("00000000-0000-0000-0000-00000000510w".replace("w", "0"))) {
            SLOW_CALLS.incrementAndGet();
            try {
              Thread.sleep(1500);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          int status = STATUS.getOrDefault(id, 200);
          byte[] body =
              status == 200
                  ? ("{\"listingId\":\""
                          + id
                          + "\",\"hostAccountId\":\""
                          + HOST
                          + "\",\"status\":\"PUBLISHED\",\"bookable\":true,\"unbookableReason\":null,"
                          + "\"pricePerHour\":\"60.00\",\"currency\":\"INR\",\"instantBook\":false,"
                          + "\"cancellationPolicy\":\"STRICT\",\"bufferMinutes\":15,"
                          + "\"maxVehicleType\":\"SUV\",\"timeZone\":\"Asia/Kolkata\"}")
                      .getBytes(StandardCharsets.UTF_8)
                  : "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  static HttpListingQuotes client(String baseUrl, Duration timeout) {
    return new HttpListingQuotes(RestClient.builder(), baseUrl, timeout);
  }

  static String base() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  static final Instant START = Instant.parse("2030-01-07T10:00:00Z");

  @Test
  void parses_the_quote_and_forwards_the_driver_token() {
    UUID listing = UUID.randomUUID();
    Quote q =
        client(base(), Duration.ofSeconds(2))
            .quote(listing, START, START.plusSeconds(3600), "tok-123");
    assertThat(q.listingId()).isEqualTo(listing);
    assertThat(q.hostAccountId()).isEqualTo(HOST);
    assertThat(q.pricePerHour()).isEqualByComparingTo("60.00");
    assertThat(q.cancellationPolicy()).isEqualTo("STRICT");
    assertThat(q.bufferMinutes()).isEqualTo(15);
    assertThat(LAST_AUTH.get(listing.toString())).isEqualTo("Bearer tok-123");
  }

  @Test
  void maps_errors_to_problems() {
    UUID missing = UUID.randomUUID();
    STATUS.put(missing.toString(), 404);
    UUID broken = UUID.randomUUID();
    STATUS.put(broken.toString(), 500);
    HttpListingQuotes c = client(base(), Duration.ofSeconds(2));
    assertThatThrownBy(() -> c.quote(missing, START, START.plusSeconds(3600), "t"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    assertThatThrownBy(() -> c.quote(broken, START, START.plusSeconds(3600), "t"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.slug()).isEqualTo("listing-unavailable"));
  }

  @Test
  void unreachable_or_slow_listing_is_503_after_one_retry() {
    HttpListingQuotes down = client("http://localhost:1", Duration.ofMillis(300));
    assertThatThrownBy(() -> down.quote(UUID.randomUUID(), START, START.plusSeconds(3600), "t"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));

    UUID slow = UUID.fromString("00000000-0000-0000-0000-000000005100");
    HttpListingQuotes impatient = client(base(), Duration.ofMillis(300));
    assertThatThrownBy(() -> impatient.quote(slow, START, START.plusSeconds(3600), "t"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.slug()).isEqualTo("listing-unavailable"));
    // The retry reaches the (single-threaded) stub after the first slow call finishes.
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(SLOW_CALLS.get()).isEqualTo(2));
  }
}
