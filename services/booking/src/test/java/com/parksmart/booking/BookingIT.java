package com.parksmart.booking;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parksmart.booking.application.BookingSaga;
import com.parksmart.booking.application.port.BookingRepository.Timer;
import com.parksmart.booking.application.port.ListingQuotes;
import com.parksmart.common.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Booking tests: a settable clock (timers, check-in windows) and an in-memory Listing quote stub
 * (the real HTTP client is covered by HttpListingQuotesIT).
 */
@Import(BookingIT.Stubs.class)
abstract class BookingIT extends IntegrationTest {

  /** A clock tests can move. Starts at the real time, truncated to the minute. */
  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MINUTES));

    void set(Instant instant) {
      now.set(instant);
    }

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  /** Listing as Booking sees it: quotes per listing ID, configurable per test. */
  static final class StubListings implements ListingQuotes {
    final Map<UUID, Quote> quotes = new ConcurrentHashMap<>();

    UUID listing(UUID host, boolean instantBook, String policy, int buffer) {
      UUID id = UUID.randomUUID();
      quotes.put(
          id,
          new Quote(
              id,
              host,
              "PUBLISHED",
              true,
              null,
              new BigDecimal("60.00"),
              "INR",
              instantBook,
              policy,
              buffer));
      return id;
    }

    @Override
    public Quote quote(UUID listingId, Instant start, Instant end, String token) {
      Quote q = quotes.get(listingId);
      if (q == null) {
        throw ApiException.notFound("Listing");
      }
      return q;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Stubs {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    StubListings stubListings() {
      return new StubListings();
    }
  }

  @Autowired MutableClock clock;
  @Autowired StubListings listings;
  @Autowired BookingSaga saga;

  @BeforeEach
  void resetClock() {
    clock.set(Instant.now().truncatedTo(ChronoUnit.MINUTES));
  }

  /** A 15-minute-aligned instant {@code hours} from now. */
  Instant slot(long hours) {
    long grid = 15 * 60;
    long epoch = clock.instant().plus(Duration.ofHours(hours)).getEpochSecond();
    return Instant.ofEpochSecond((epoch / grid + 1) * grid);
  }

  static Map<String, Object> body(UUID listingId, Instant start, Instant end) {
    Map<String, Object> body = new HashMap<>();
    body.put("listingId", listingId.toString());
    body.put("startTime", start.toString());
    body.put("endTime", end.toString());
    return body;
  }

  ResultActions book(User driver, String key, Map<String, Object> body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post("/api/v1/bookings")
            .header("Authorization", "Bearer " + driver.token())
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(json.writeValueAsString(body)));
  }

  /** Creates a booking and returns its JSON. */
  JsonNode booked(User driver, UUID listing, Instant start, Instant end) throws Exception {
    return body(
        book(driver, "key-" + UUID.randomUUID(), body(listing, start, end))
            .andExpect(status().isAccepted()));
  }

  UUID id(JsonNode booking) {
    return UUID.fromString(booking.get("id").asText());
  }

  /** Simulates Payment's PaymentAuthorized for a booking. */
  void authorize(UUID bookingId) {
    var row =
        jdbc.sql(
                "SELECT driver_account_id, host_account_id, total_amount FROM bookings WHERE id = :id")
            .param("id", bookingId)
            .query()
            .singleRow();
    saga.paymentAuthorized(
        UUID.randomUUID(),
        bookingId,
        (UUID) row.get("driver_account_id"),
        (UUID) row.get("host_account_id"),
        (BigDecimal) row.get("total_amount"),
        "INR");
  }

  UUID driverOf(UUID bookingId) {
    return jdbc.sql("SELECT driver_account_id FROM bookings WHERE id = :id")
        .param("id", bookingId)
        .query(UUID.class)
        .single();
  }

  UUID hostOf(UUID bookingId) {
    return jdbc.sql("SELECT host_account_id FROM bookings WHERE id = :id")
        .param("id", bookingId)
        .query(UUID.class)
        .single();
  }

  int runTimer(Timer timer) {
    return saga.runTimer(timer, 100);
  }

  String statusOf(UUID bookingId) {
    return jdbc.sql("SELECT status FROM bookings WHERE id = :id")
        .param("id", bookingId)
        .query(String.class)
        .single();
  }
}
