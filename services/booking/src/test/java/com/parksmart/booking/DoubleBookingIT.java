package com.parksmart.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Hard rule 5: concurrent requests for overlapping time cannot both win. */
class DoubleBookingIT extends BookingIT {

  @Test
  void of_many_concurrent_overlapping_requests_exactly_one_wins() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 15);
    Instant start = slot(24);
    int attempts = 24;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      User driver = driver();
      // Every window overlaps the others: same start, varying length.
      Instant end = start.plus(Duration.ofMinutes(30L + 15L * (i % 6)));
      results.add(
          pool.submit(() -> {
            go.await();
            return book(driver, "race-" + UUID.randomUUID(), body(listing, start, end))
                .andReturn()
                .getResponse()
                .getStatus();
          }));
    }
    go.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get());
    }
    pool.shutdown();

    assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(attempts - 1);
    Long active =
        jdbc.sql(
                "SELECT count(*) FROM bookings WHERE listing_id = :l AND status = 'PENDING_PAYMENT'")
            .param("l", listing)
            .query(Long.class)
            .single();
    assertThat(active).isEqualTo(1);
  }

  @Test
  void the_database_rejects_an_overlap_written_directly() {
    UUID listing = UUID.randomUUID();
    String insert =
        """
        INSERT INTO bookings (id, driver_account_id, host_account_id, listing_id, start_time,
          end_time, blocked_until, blocked_range, status, price_per_hour_snapshot, total_amount,
          instant_book, cancellation_policy, buffer_minutes, idempotency_key, request_hash,
          created_at, updated_at)
        VALUES (:id, gen_random_uuid(), gen_random_uuid(), :listing, :start, :end, :end,
          tstzrange(:start, :end, '[)'), :status, 10, 10, true, 'FLEXIBLE', 0, :key, 'h', now(), now())
        """;
    java.time.OffsetDateTime s = java.time.OffsetDateTime.parse("2031-01-01T10:00:00Z");
    jdbc.sql(insert)
        .param("id", UUID.randomUUID())
        .param("listing", listing)
        .param("start", s)
        .param("end", s.plusHours(2))
        .param("status", "CONFIRMED")
        .param("key", "k1")
        .update();
    // A cancelled booking does not block.
    jdbc.sql(insert)
        .param("id", UUID.randomUUID())
        .param("listing", listing)
        .param("start", s)
        .param("end", s.plusHours(2))
        .param("status", "CANCELLED_BY_DRIVER")
        .param("key", "k2")
        .update();
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            jdbc.sql(insert)
                .param("id", UUID.randomUUID())
                .param("listing", listing)
                .param("start", s.plusHours(1))
                .param("end", s.plusHours(3))
                .param("status", "AWAITING_HOST_APPROVAL")
                .param("key", "k3")
                .update())
        .hasMessageContaining("excl_booking_overlap");
    // Touching ranges ([10,12) and [12,13)) do not overlap.
    jdbc.sql(insert)
        .param("id", UUID.randomUUID())
        .param("listing", listing)
        .param("start", s.plusHours(2))
        .param("end", s.plusHours(3))
        .param("status", "PENDING_PAYMENT")
        .param("key", "k4")
        .update();
  }
}
