package com.parksmart.adminbff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.parksmart.adminbff.domain.Facts.Booking;
import com.parksmart.adminbff.domain.Facts.Listing;
import com.parksmart.adminbff.domain.Facts.ListingRow;
import com.parksmart.adminbff.domain.Facts.Party;
import com.parksmart.adminbff.domain.Facts.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CompositionTest {

  static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
  static final UUID LISTING = UUID.randomUUID();
  static final UUID HOST = UUID.randomUUID();
  static final UUID DRIVER = UUID.randomUUID();
  static final UUID BOOKING = UUID.randomUUID();

  static ListingRow row() {
    return new ListingRow(
        LISTING, "Covered spot", "PENDING_REVIEW", new BigDecimal("60.00"), "INR", null);
  }

  static Listing listing(UUID id, UUID host) {
    return new Listing(id, host, "Covered spot", "PENDING_REVIEW", "1 Main St", 2, 5, null, NOW);
  }

  static Party party(UUID id) {
    return new Party(id, "Asha", null, "4.50", 3);
  }

  static Booking booking() {
    return new Booking(
        BOOKING,
        LISTING,
        DRIVER,
        HOST,
        "CONFIRMED",
        NOW,
        NOW.plusSeconds(7200),
        new BigDecimal("60.00"),
        new BigDecimal("120.00"),
        "INR",
        "MODERATE",
        null,
        NOW,
        NOW);
  }

  static Payment payment(UUID bookingId) {
    return new Payment(
        UUID.randomUUID(),
        bookingId,
        new BigDecimal("120.00"),
        "INR",
        "CAPTURED",
        null,
        BigDecimal.ZERO,
        NOW);
  }

  @Nested
  class Lookups {

    @Test
    void found_absent_and_failed() {
      assertThat(Lookup.found("x").orNull()).isEqualTo("x");
      assertThat(Lookup.absent().orNull()).isNull();
      assertThat(Lookup.absent().isFailed()).isFalse();
      assertThat(Lookup.failed().isFailed()).isTrue();
      assertThat(Lookup.ofNullable(null)).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void a_value_that_does_not_belong_is_a_failure() {
      assertThat(Lookup.found("x").requireThat("y"::equals).isFailed()).isTrue();
      assertThat(Lookup.found("x").requireThat("x"::equals).orNull()).isEqualTo("x");
      assertThat(Lookup.<String>absent().requireThat("y"::equals).isFailed()).isFalse();
    }
  }

  @Nested
  class ModerationQueue {

    @Test
    void complete_item() {
      var item =
          ModerationQueueItem.compose(
              row(), Lookup.found(listing(LISTING, HOST)), Lookup.found(party(HOST)));
      assertThat(item.partial()).isFalse();
      assertThat(item.details().address()).isEqualTo("1 Main St");
      assertThat(item.host().displayName()).isEqualTo("Asha");
    }

    @Test
    void failed_details_make_the_item_partial_and_drop_the_host() {
      var item = ModerationQueueItem.compose(row(), Lookup.failed(), Lookup.found(party(HOST)));
      assertThat(item.partial()).isTrue();
      assertThat(item.details()).isNull();
      assertThat(item.host()).isNull();
    }

    @Test
    void host_without_a_profile_is_not_partial() {
      var item =
          ModerationQueueItem.compose(row(), Lookup.found(listing(LISTING, HOST)), Lookup.absent());
      assertThat(item.partial()).isFalse();
      assertThat(item.host()).isNull();
    }

    @Test
    void details_vanished_meanwhile_is_not_partial() {
      var item = ModerationQueueItem.compose(row(), Lookup.absent(), Lookup.absent());
      assertThat(item.partial()).isFalse();
      assertThat(item.details()).isNull();
    }

    @Test
    void mismatched_details_or_profile_are_dropped() {
      var otherListing =
          ModerationQueueItem.compose(
              row(), Lookup.found(listing(UUID.randomUUID(), HOST)), Lookup.found(party(HOST)));
      assertThat(otherListing.partial()).isTrue();
      assertThat(otherListing.details()).isNull();

      var otherHost =
          ModerationQueueItem.compose(
              row(), Lookup.found(listing(LISTING, HOST)), Lookup.found(party(UUID.randomUUID())));
      assertThat(otherHost.partial()).isTrue();
      assertThat(otherHost.host()).isNull();
      assertThat(otherHost.details()).isNotNull();
    }
  }

  @Nested
  class Cases {

    @Test
    void complete_case() {
      var c =
          BookingCase.compose(
              booking(),
              Lookup.found(payment(BOOKING)),
              Lookup.found(listing(LISTING, HOST)),
              Lookup.found(party(DRIVER)),
              Lookup.found(party(HOST)));
      assertThat(c.partial()).isFalse();
      assertThat(c.payment().status()).isEqualTo("CAPTURED");
      assertThat(c.driver().accountId()).isEqualTo(DRIVER);
      assertThat(c.host().accountId()).isEqualTo(HOST);
    }

    @Test
    void no_intent_yet_is_not_partial() {
      var c =
          BookingCase.compose(
              booking(),
              Lookup.absent(),
              Lookup.found(listing(LISTING, HOST)),
              Lookup.absent(),
              Lookup.absent());
      assertThat(c.partial()).isFalse();
      assertThat(c.payment()).isNull();
    }

    @Test
    void any_failed_or_foreign_part_makes_it_partial() {
      assertThat(
              BookingCase.compose(
                      booking(),
                      Lookup.found(payment(UUID.randomUUID())),
                      Lookup.absent(),
                      Lookup.absent(),
                      Lookup.absent())
                  .partial())
          .isTrue();
      assertThat(
              BookingCase.compose(
                      booking(), Lookup.absent(), Lookup.failed(), Lookup.absent(), Lookup.absent())
                  .partial())
          .isTrue();
      var swapped =
          BookingCase.compose(
              booking(),
              Lookup.absent(),
              Lookup.absent(),
              Lookup.found(party(HOST)),
              Lookup.found(party(DRIVER)));
      assertThat(swapped.partial()).isTrue();
      assertThat(swapped.driver()).isNull();
      assertThat(swapped.host()).isNull();
    }
  }
}
