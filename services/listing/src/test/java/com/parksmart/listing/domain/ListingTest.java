package com.parksmart.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Enums.UnbookableReason;
import com.parksmart.listing.domain.Values.Blackout;
import com.parksmart.listing.domain.Values.GeoPoint;
import com.parksmart.listing.domain.Values.HostEligibility;
import com.parksmart.listing.domain.Values.PriceBounds;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingTest {

  static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
  static final PriceBounds BOUNDS =
      new PriceBounds(new BigDecimal("5.00"), new BigDecimal("2000.00"));
  static final HostEligibility ELIGIBLE = new HostEligibility(true, true, false);

  static ListingTerms terms() {
    return new ListingTerms(
        "Covered spot near Baner",
        null,
        "12 Baner Road, Pune",
        new GeoPoint(18.5, 73.8),
        "Asia/Kolkata",
        new BigDecimal("60"),
        "INR",
        VehicleType.SUV,
        Set.of(Amenity.CCTV),
        true,
        CancellationPolicy.MODERATE,
        15);
  }

  static Listing draft() {
    return Listing.create(UUID.randomUUID(), UUID.randomUUID(), terms(), BOUNDS, T0);
  }

  static ListingTerms patch(String title, String address) {
    return new ListingTerms(
        title, null, address, null, null, null, null, null, null, null, null, null);
  }

  static Listing publishable() {
    Listing l = draft();
    ListingPhoto p =
        l.startPhotoUpload(
            UUID.randomUUID(), PhotoContentType.PNG, 10, 100, T0, Duration.ofMinutes(10));
    l.confirmPhoto(p.id(), l.publicPhotoKey(p.id()), "https://cdn/x", T0);
    l.replaceRules(
        java.util.Arrays.stream(DayOfWeek.values())
            .map(d -> new AvailabilityRule(d, 0, 1440))
            .toList(),
        T0);
    return l;
  }

  static Listing live() {
    Listing l = publishable();
    l.submit(true, ELIGIBLE, T0);
    l.approve(ELIGIBLE, T0);
    return l;
  }

  @Test
  void create_requires_terms_and_normalizes_price() {
    Listing l = draft();
    assertThat(l.status()).isEqualTo(ListingStatus.DRAFT);
    assertThat(l.pricePerHour()).isEqualByComparingTo("60.00").hasToString("60.00");
    assertThatThrownBy(() ->
            Listing.create(
                UUID.randomUUID(), UUID.randomUUID(), patch("Only a title", null), BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-listing");
  }

  @Test
  void price_and_buffer_bounds() {
    Listing l = draft();
    ListingTerms cheap =
        new ListingTerms(
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("4.99"),
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> l.update(cheap, BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-pricePerHour");
    ListingTerms fine =
        new ListingTerms(
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("10.555"),
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> l.update(fine, BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-pricePerHour");
    ListingTerms buffer =
        new ListingTerms(null, null, null, null, null, null, null, null, null, null, null, 125);
    assertThatThrownBy(() -> l.update(buffer, BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-bufferMinutes");
  }

  @Test
  void submit_lists_unmet_requirements() {
    Listing l = draft();
    assertThatThrownBy(() -> l.submit(false, HostEligibility.UNKNOWN, T0))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("photo")
        .hasMessageContaining("availability rule")
        .hasMessageContaining("HOST role")
        .hasMessageContaining("verified phone")
        .hasMessageContaining("profile photo");
    Listing ready = publishable();
    assertThatThrownBy(() -> ready.submit(true, new HostEligibility(true, true, true), T0))
        .hasMessageContaining("good standing");
    ready.submit(true, ELIGIBLE, T0);
    assertThat(ready.status()).isEqualTo(ListingStatus.PENDING_REVIEW);
  }

  @Test
  void edits_keep_published_status_but_relocation_needs_review() {
    Listing l = live();
    assertThat(l.update(patch("A brand new title", null), BOUNDS, T0))
        .isEqualTo(Listing.Change.PUBLIC);
    assertThat(l.status()).isEqualTo(ListingStatus.PUBLISHED);
    assertThat(l.update(patch("A brand new title", null), BOUNDS, T0))
        .isEqualTo(Listing.Change.NONE);
    assertThat(l.update(patch(null, "1 Other Road, Pune"), BOUNDS, T0))
        .isEqualTo(Listing.Change.RELOCATED);
    assertThat(l.status()).isEqualTo(ListingStatus.PENDING_REVIEW);
    assertThatThrownBy(() -> l.update(patch("Anything else", null), BOUNDS, T0))
        .extracting("code")
        .isEqualTo("listing-not-editable");
  }

  @Test
  void illegal_transitions() {
    Listing l = draft();
    assertThatThrownBy(() -> l.approve(ELIGIBLE, T0))
        .extracting("code")
        .isEqualTo("illegal-transition");
    assertThatThrownBy(() -> l.pause(T0)).extracting("code").isEqualTo("illegal-transition");
    l.archive(T0);
    assertThat(l.deletedAt()).isEqualTo(T0);
    assertThatThrownBy(() -> l.archive(T0)).extracting("code").isEqualTo("listing-archived");
    assertThatThrownBy(() -> l.replaceRules(List.of(), T0))
        .extracting("code")
        .isEqualTo("listing-archived");
  }

  @Test
  void suspension_pauses_only_published_listings_and_blocks_resume() {
    Listing l = live();
    assertThat(l.pauseForSuspension(T0)).isTrue();
    assertThat(l.pauseForSuspension(T0)).isFalse();
    assertThatThrownBy(() -> l.resume(new HostEligibility(true, true, true), T0))
        .extracting("code")
        .isEqualTo("host-suspended");
  }

  @Test
  void last_photo_rule_and_expired_uploads() {
    Listing l = live();
    UUID photo = l.readyPhotos().get(0).id();
    assertThatThrownBy(() -> l.removePhoto(photo, T0)).extracting("code").isEqualTo("last-photo");
    ListingPhoto pending =
        l.startPhotoUpload(
            UUID.randomUUID(), PhotoContentType.JPEG, 10, 100, T0, Duration.ofMinutes(10));
    assertThatThrownBy(() -> l.pendingPhoto(pending.id(), T0.plus(Duration.ofMinutes(10))))
        .extracting("code")
        .isEqualTo("upload-expired");
    assertThatThrownBy(() ->
            l.startPhotoUpload(
                UUID.randomUUID(), PhotoContentType.JPEG, 101, 100, T0, Duration.ofMinutes(1)))
        .extracting("code")
        .isEqualTo("invalid-contentLength");
  }

  // ---------------------------------------------------------------- availability

  /** 2030-01-07 is a Monday. Asia/Kolkata is UTC+05:30, no DST. */
  static Instant ist(String localDateTime) {
    return java.time.LocalDateTime.parse(localDateTime)
        .atZone(java.time.ZoneId.of("Asia/Kolkata"))
        .toInstant();
  }

  static Listing withRules(AvailabilityRule... rules) {
    Listing l = live();
    l.replaceRules(List.of(rules), T0);
    return l;
  }

  @Test
  void a_window_inside_one_rule_is_bookable() {
    Listing l = withRules(new AvailabilityRule(DayOfWeek.MONDAY, 8 * 60, 20 * 60));
    assertThat(l.bookability(ist("2030-01-07T09:00"), ist("2030-01-07T11:00"))).isEmpty();
    assertThat(l.bookability(ist("2030-01-07T08:00"), ist("2030-01-07T20:00"))).isEmpty();
    assertThat(l.bookability(ist("2030-01-07T07:45"), ist("2030-01-07T09:00")))
        .contains(UnbookableReason.OUTSIDE_AVAILABILITY);
    assertThat(l.bookability(ist("2030-01-07T19:00"), ist("2030-01-07T20:15")))
        .contains(UnbookableReason.OUTSIDE_AVAILABILITY);
  }

  @Test
  void adjacent_rules_join_and_gaps_break_coverage() {
    Listing joined =
        withRules(
            new AvailabilityRule(DayOfWeek.MONDAY, 8 * 60, 12 * 60),
            new AvailabilityRule(DayOfWeek.MONDAY, 12 * 60, 18 * 60));
    assertThat(joined.bookability(ist("2030-01-07T10:00"), ist("2030-01-07T14:00"))).isEmpty();
    Listing gap =
        withRules(
            new AvailabilityRule(DayOfWeek.MONDAY, 8 * 60, 12 * 60),
            new AvailabilityRule(DayOfWeek.MONDAY, 13 * 60, 18 * 60));
    assertThat(gap.bookability(ist("2030-01-07T10:00"), ist("2030-01-07T14:00")))
        .contains(UnbookableReason.OUTSIDE_AVAILABILITY);
  }

  @Test
  void overnight_windows_need_both_days() {
    Listing l =
        withRules(
            new AvailabilityRule(DayOfWeek.MONDAY, 18 * 60, 1440),
            new AvailabilityRule(DayOfWeek.TUESDAY, 0, 9 * 60));
    assertThat(l.bookability(ist("2030-01-07T22:00"), ist("2030-01-08T08:00"))).isEmpty();
    assertThat(l.bookability(ist("2030-01-07T22:00"), ist("2030-01-08T10:00")))
        .contains(UnbookableReason.OUTSIDE_AVAILABILITY);
    // Ending exactly at midnight needs nothing from Tuesday.
    Listing mondayEvening = withRules(new AvailabilityRule(DayOfWeek.MONDAY, 18 * 60, 1440));
    assertThat(mondayEvening.bookability(ist("2030-01-07T20:00"), ist("2030-01-08T00:00")))
        .isEmpty();
  }

  @Test
  void blackouts_and_status_win_over_rules() {
    Listing l = withRules(new AvailabilityRule(DayOfWeek.MONDAY, 0, 1440));
    l.addBlackout(
        new Blackout(UUID.randomUUID(), ist("2030-01-07T12:00"), ist("2030-01-07T13:00"), null),
        ist("2030-01-01T00:00"));
    assertThat(l.bookability(ist("2030-01-07T11:00"), ist("2030-01-07T12:00"))).isEmpty();
    assertThat(l.bookability(ist("2030-01-07T11:00"), ist("2030-01-07T12:15")))
        .contains(UnbookableReason.BLACKED_OUT);
    l.pause(T0);
    assertThat(l.bookability(ist("2030-01-07T09:00"), ist("2030-01-07T10:00")))
        .contains(UnbookableReason.NOT_PUBLISHED);
    assertThatThrownBy(() -> l.bookability(ist("2030-01-07T10:00"), ist("2030-01-07T09:00")))
        .extracting("code")
        .isEqualTo("invalid-window");
  }

  @Test
  void rules_must_not_overlap_and_times_parse() {
    Listing l = draft();
    assertThatThrownBy(() ->
            l.replaceRules(
                List.of(
                    new AvailabilityRule(DayOfWeek.FRIDAY, 600, 700),
                    new AvailabilityRule(DayOfWeek.FRIDAY, 650, 800)),
                T0))
        .extracting("code")
        .isEqualTo("invalid-availability");
    AvailabilityRule r = AvailabilityRule.parse(DayOfWeek.SUNDAY, "06:30", "24:00");
    assertThat(r.startMinute()).isEqualTo(390);
    assertThat(r.endTime()).isEqualTo("24:00");
    assertThatThrownBy(() -> AvailabilityRule.parse(DayOfWeek.SUNDAY, "24:00", "24:00"))
        .extracting("code")
        .isEqualTo("invalid-availability");
  }

  @Test
  void blackouts_must_end_in_the_future() {
    Listing l = draft();
    Blackout past =
        new Blackout(UUID.randomUUID(), T0.minusSeconds(7200), T0.minusSeconds(3600), null);
    assertThatThrownBy(() -> l.addBlackout(past, T0))
        .extracting("code")
        .isEqualTo("invalid-blackout");
    assertThatThrownBy(() -> new Blackout(UUID.randomUUID(), T0, T0, null))
        .extracting("code")
        .isEqualTo("invalid-blackout");
  }

  @Test
  void dst_fall_back_windows_must_cover_the_repeated_hour() {
    ListingTerms ny =
        new ListingTerms(
            null, null, null, null, "America/New_York", null, null, null, null, null, null, null);
    Listing l = live();
    l.update(ny, BOUNDS, T0);
    // 2030-11-03 is a Sunday; clocks go back 02:00 EDT -> 01:00 EST.
    Instant firstPass = Instant.parse("2030-11-03T05:45:00Z"); // 01:45 EDT
    Instant secondPass = Instant.parse("2030-11-03T06:15:00Z"); // 01:15 EST
    l.replaceRules(List.of(new AvailabilityRule(DayOfWeek.SUNDAY, 3 * 60, 1440)), T0);
    assertThat(l.bookability(firstPass, secondPass))
        .contains(UnbookableReason.OUTSIDE_AVAILABILITY);
    l.replaceRules(List.of(new AvailabilityRule(DayOfWeek.SUNDAY, 0, 1440)), T0);
    assertThat(l.bookability(firstPass, secondPass)).isEmpty();
  }

  @Test
  void photos_are_frozen_during_review_and_old_blackouts_are_pruned() {
    Listing l = publishable();
    l.submit(true, ELIGIBLE, T0);
    assertThatThrownBy(() ->
            l.startPhotoUpload(
                UUID.randomUUID(), PhotoContentType.PNG, 10, 100, T0, Duration.ofMinutes(10)))
        .extracting("code")
        .isEqualTo("listing-not-editable");
    UUID photo = l.readyPhotos().get(0).id();
    assertThatThrownBy(() -> l.reorderPhotos(List.of(photo), T0))
        .extracting("code")
        .isEqualTo("listing-not-editable");

    Listing d = draft();
    d.addBlackout(new Blackout(UUID.randomUUID(), T0, T0.plusSeconds(3600), null), T0);
    Instant later = T0.plus(Duration.ofDays(40));
    d.addBlackout(new Blackout(UUID.randomUUID(), later, later.plusSeconds(3600), null), later);
    assertThat(d.blackouts()).hasSize(1);
    Instant tooFar = T0.plus(Duration.ofDays(800));
    assertThatThrownBy(() ->
            d.addBlackout(
                new Blackout(UUID.randomUUID(), tooFar, tooFar.plusSeconds(60), null), T0))
        .extracting("code")
        .isEqualTo("invalid-blackout");
  }

  @Test
  void invisible_characters_are_rejected() {
    Listing l = draft();
    assertThatThrownBy(() -> l.update(patch("Nice spot \u202Egnp.exe", null), BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-title");
    assertThatThrownBy(() -> l.update(patch("Zero\u200Bwidth title", null), BOUNDS, T0))
        .extracting("code")
        .isEqualTo("invalid-title");
  }
}
