package com.parksmart.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parksmart.search.application.Projection;
import com.parksmart.search.application.port.ListingSearchIndex.Snapshot;
import com.parksmart.search.application.port.ListingSearchIndex.Window;
import com.parksmart.search.domain.Availability.Rule;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The projection is fed directly (Kafka is covered by SearchKafkaIT); search runs over HTTP. */
class SearchIT extends IntegrationTest {

  @Autowired Projection projection;

  /** Each test searches its own far-away spot so tests never see each other's listings. */
  static double[] spot() {
    return new double[] {-60 + Math.random() * 120, -170 + Math.random() * 340};
  }

  static List<Rule> allWeek() {
    return Arrays.stream(DayOfWeek.values()).map(d -> new Rule(d, 0, 1440)).toList();
  }

  UUID publish(
      double lat,
      double lng,
      String price,
      String vehicle,
      List<String> amenities,
      boolean instant,
      List<Rule> rules,
      Instant at) {
    UUID id = UUID.randomUUID();
    publish(id, lat, lng, price, vehicle, amenities, instant, rules, "PUBLISHED", at);
    return id;
  }

  void publish(
      UUID id,
      double lat,
      double lng,
      String price,
      String vehicle,
      List<String> amenities,
      boolean instant,
      List<Rule> rules,
      String status,
      Instant at) {
    projection.snapshot(
        UUID.randomUUID(),
        new Snapshot(
            id,
            UUID.randomUUID(),
            status,
            "Spot " + price,
            "Some road",
            lat,
            lng,
            "UTC",
            new BigDecimal(price),
            "INR",
            vehicle,
            amenities,
            instant,
            "FLEXIBLE",
            15,
            "https://cdn/x.png",
            rules,
            List.of()),
        at);
  }

  JsonNode search(double[] p, String extra) throws Exception {
    return body(
        get("/api/v1/search/listings?lat=" + p[0] + "&lng=" + p[1] + "&radiusKm=10" + extra, null)
            .andExpect(status().isOk()));
  }

  static List<String> ids(JsonNode page) {
    List<String> out = new java.util.ArrayList<>();
    page.get("items").forEach(i -> out.add(i.get("listingId").asText()));
    return out;
  }

  @Test
  void nearest_first_within_the_radius_with_distance_and_freshness() throws Exception {
    double[] p = spot();
    Instant t = Instant.now();
    UUID near = publish(p[0] + 0.001, p[1], "50.00", "SUV", List.of(), true, allWeek(), t);
    UUID far = publish(p[0] + 0.05, p[1], "40.00", "SUV", List.of(), true, allWeek(), t);
    publish(p[0] + 1.0, p[1], "10.00", "SUV", List.of(), true, allWeek(), t); // ~111 km
    JsonNode page = search(p, "");
    assertThat(ids(page)).containsExactly(near.toString(), far.toString());
    JsonNode first = page.get("items").get(0);
    assertThat(first.get("distanceKm").asDouble()).isBetween(0.05, 0.2);
    assertThat(first.get("pricePerHour").asText()).isEqualTo("50.00");
    assertThat(first.get("lastUpdatedFromEventAt").asText()).isNotBlank();

    JsonNode byPrice = search(p, "&sort=price");
    assertThat(ids(byPrice)).containsExactly(far.toString(), near.toString());
  }

  @Test
  void filters_vehicle_price_amenities_and_instant_book() throws Exception {
    double[] p = spot();
    Instant t = Instant.now();
    UUID bike = publish(p[0], p[1], "20.00", "MOTORCYCLE", List.of("CCTV"), false, allWeek(), t);
    UUID suv =
        publish(
            p[0], p[1] + 0.001, "80.00", "SUV", List.of("CCTV", "EV_CHARGER"), true, allWeek(), t);
    assertThat(ids(search(p, "&vehicleType=SEDAN"))).containsExactly(suv.toString());
    assertThat(ids(search(p, "&maxPricePerHour=50"))).containsExactly(bike.toString());
    assertThat(ids(search(p, "&amenities=cctv,ev_charger"))).containsExactly(suv.toString());
    assertThat(ids(search(p, "&instantBook=false"))).containsExactly(bike.toString());
  }

  @Test
  void a_window_needs_rules_no_blackout_and_no_overlapping_booking() throws Exception {
    double[] p = spot();
    Instant t = Instant.now();
    Instant start = Instant.parse("2030-01-07T10:00:00Z"); // Monday, UTC listing
    Instant end = start.plusSeconds(7200);
    UUID open = publish(p[0], p[1], "30.00", "SUV", List.of(), true, allWeek(), t);
    UUID mornings =
        publish(
            p[0],
            p[1] + 0.001,
            "30.00",
            "SUV",
            List.of(),
            true,
            List.of(new Rule(DayOfWeek.MONDAY, 6 * 60, 9 * 60)),
            t);
    UUID blacked = publish(p[0], p[1] + 0.002, "30.00", "SUV", List.of(), true, allWeek(), t);
    UUID booked = publish(p[0], p[1] + 0.003, "30.00", "SUV", List.of(), true, allWeek(), t);
    projection.availability(
        UUID.randomUUID(),
        blacked,
        allWeek(),
        List.of(new Window(start.plusSeconds(3600), start.plusSeconds(5400))),
        t.plusSeconds(1));
    UUID booking = UUID.randomUUID();
    // Ends 15 minutes before our start; with the 15-minute buffer it still touches only.
    projection.busy(
        UUID.randomUUID(), booking, booked, start.minusSeconds(3600), start.minusSeconds(900));
    String window = "&startTime=" + start + "&endTime=" + end;
    assertThat(ids(search(p, window)))
        .containsExactlyInAnyOrder(open.toString(), booked.toString());

    UUID overlapping = UUID.randomUUID();
    projection.busy(
        UUID.randomUUID(), overlapping, booked, end.plusSeconds(600), end.plusSeconds(4000));
    // Our end + 15-minute buffer reaches into that booking.
    JsonNode page = search(p, window);
    assertThat(ids(page)).containsExactly(open.toString());
    assertThat(page.get("items").get(0).get("estimatedTotal").asText()).isEqualTo("60.00");

    projection.free(UUID.randomUUID(), overlapping);
    projection.busy(
        UUID.randomUUID(),
        overlapping,
        booked,
        end.plusSeconds(600),
        end.plusSeconds(4000)); // late duplicate
    assertThat(ids(search(p, window))).contains(booked.toString());
    assertThat(mornings).isNotNull();
  }

  @Test
  void hidden_listings_disappear_and_stale_snapshots_do_not_bring_them_back() throws Exception {
    double[] p = spot();
    Instant t = Instant.now();
    UUID id = publish(p[0], p[1], "30.00", "SUV", List.of(), true, allWeek(), t);
    projection.hide(UUID.randomUUID(), id, t.plusSeconds(10));
    assertThat(ids(search(p, ""))).isEmpty();
    get("/api/v1/search/listings/" + id, null).andExpect(status().isNotFound());
    publish(
        id, p[0], p[1], "30.00", "SUV", List.of(), true, allWeek(), "PUBLISHED", t.plusSeconds(5));
    assertThat(ids(search(p, ""))).isEmpty();
    publish(
        id, p[0], p[1], "35.00", "SUV", List.of(), true, allWeek(), "PUBLISHED", t.plusSeconds(20));
    get("/api/v1/search/listings/" + id, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pricePerHour").value("35.00"));
    projection.rating(UUID.randomUUID(), id, new BigDecimal("4.50"), 2, t);
    get("/api/v1/search/listings/" + id, null)
        .andExpect(jsonPath("$.ratingAvg").value("4.50"))
        .andExpect(jsonPath("$.ratingCount").value(2));
  }

  @Test
  void pages_continue_with_a_cursor_and_bad_input_is_400() throws Exception {
    double[] p = spot();
    Instant t = Instant.now();
    for (int i = 0; i < 5; i++) {
      publish(p[0] + i * 0.001, p[1], "30.00", "SUV", List.of(), true, allWeek(), t);
    }
    JsonNode first = search(p, "&limit=2");
    assertThat(first.get("items")).hasSize(2);
    JsonNode second = search(p, "&limit=2&cursor=" + first.get("nextCursor").asText());
    JsonNode third = search(p, "&limit=2&cursor=" + second.get("nextCursor").asText());
    assertThat(third.get("items")).hasSize(1);
    assertThat(third.get("nextCursor").isNull()).isTrue();
    List<String> all = new java.util.ArrayList<>(ids(first));
    all.addAll(ids(second));
    all.addAll(ids(third));
    assertThat(all).doesNotHaveDuplicates().hasSize(5);

    get("/api/v1/search/listings?lat=91&lng=0", null).andExpect(status().isBadRequest());
    get("/api/v1/search/listings?lat=0&lng=0&radiusKm=500", null)
        .andExpect(status().isBadRequest());
    get("/api/v1/search/listings?lat=0&lng=0&startTime=2030-01-01T00:00:00Z", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-search"));
    get("/api/v1/search/listings?lat=0&lng=0&amenities=JACUZZI", null)
        .andExpect(status().isBadRequest());
    get("/api/v1/search/listings?lat=0&lng=0&cursor=nope", null).andExpect(status().isBadRequest());
    get(
            "/api/v1/search/listings?lat=0&lng=0&sort=price&cursor="
                + first.get("nextCursor").asText(),
            null)
        .andExpect(status().isBadRequest());
  }
}
