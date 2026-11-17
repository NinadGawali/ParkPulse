package com.parksmart.search.infrastructure.persistence;

import com.parksmart.search.application.port.ListingSearchIndex;
import com.parksmart.search.domain.Availability.Rule;
import com.parksmart.search.domain.SearchQuery;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ListingSearchIndex} on PostGIS. Only constant SQL fragments are concatenated; every value
 * is a bound parameter.
 */
@Repository
class PostgisSearchIndex implements ListingSearchIndex {

  private static final String POINT = "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography";
  private static final String DISTANCE_KM = "(ST_Distance(l.location, " + POINT + ") / 1000.0)";
  private static final String COLUMNS =
      "l.listing_id, l.title, l.address, l.latitude, l.longitude, l.time_zone, l.price_per_hour,"
          + " l.currency, l.max_vehicle_type, l.amenities, l.instant_book, l.cancellation_policy,"
          + " l.buffer_minutes, l.thumbnail_url, r.rating_avg, COALESCE(r.rating_count, 0) AS rating_count,"
          + " l.last_event_at";
  private static final String FROM =
      " FROM search_listings l LEFT JOIN search_ratings r ON r.listing_id = l.listing_id";

  private final JdbcClient jdbc;

  PostgisSearchIndex(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Indexed> candidates(SearchQuery q, After after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(", ")
            .append(DISTANCE_KM)
            .append(" AS distance_km")
            .append(FROM)
            .append(" WHERE l.visible")
            .append(" AND ST_DWithin(l.location, ")
            .append(POINT)
            .append(", :radius)");
    if (q.maxPricePerHour() != null) {
      sql.append(" AND l.price_per_hour <= :maxPrice");
    }
    if (q.minVehicleRank() != null) {
      sql.append(" AND l.max_vehicle_rank >= :rank");
    }
    if (!q.amenities().isEmpty()) {
      sql.append(" AND l.amenities @> CAST(:amenities AS text[])");
    }
    if (q.instantBook() != null) {
      sql.append(" AND l.instant_book = :instant");
    }
    if (q.hasWindow()) {
      sql.append(
          " AND NOT EXISTS (SELECT 1 FROM search_blackouts b WHERE b.listing_id = l.listing_id"
              + " AND b.start_at < :end AND :start < b.end_at)"
              + " AND NOT EXISTS (SELECT 1 FROM search_busy x WHERE x.listing_id = l.listing_id"
              + " AND x.ended_at IS NULL"
              + " AND x.start_at < :end + l.buffer_minutes * interval '1 minute'"
              + " AND :start < x.blocked_until)");
    }
    boolean byPrice = q.sort() == SearchQuery.Sort.PRICE;
    if (after != null) {
      sql.append(
          byPrice
              ? " AND (l.price_per_hour > :afterPrice OR (l.price_per_hour = :afterPrice AND ("
                  + DISTANCE_KM
                  + " > :afterDistance OR ("
                  + DISTANCE_KM
                  + " = :afterDistance AND l.listing_id > :afterId))))"
              : " AND ("
                  + DISTANCE_KM
                  + " > :afterDistance OR ("
                  + DISTANCE_KM
                  + " = :afterDistance AND l.listing_id > :afterId))");
    }
    sql.append(
        byPrice
            ? " ORDER BY l.price_per_hour, distance_km, l.listing_id"
            : " ORDER BY distance_km, l.listing_id");
    sql.append(" LIMIT :limit");

    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("lat", q.lat())
            .param("lng", q.lng())
            .param("radius", q.radiusKm() * 1000)
            .param("limit", limit);
    if (q.maxPricePerHour() != null) {
      spec = spec.param("maxPrice", q.maxPricePerHour());
    }
    if (q.minVehicleRank() != null) {
      spec = spec.param("rank", q.minVehicleRank());
    }
    if (!q.amenities().isEmpty()) {
      spec =
          spec.param(
              "amenities", "{" + String.join(",", q.amenities().stream().sorted().toList()) + "}");
    }
    if (q.instantBook() != null) {
      spec = spec.param("instant", q.instantBook());
    }
    if (q.hasWindow()) {
      spec = spec.param("start", ts(q.start())).param("end", ts(q.end()));
    }
    if (after != null) {
      spec = spec.param("afterDistance", after.distanceKm()).param("afterId", after.listingId());
      if (byPrice) {
        spec = spec.param("afterPrice", after.price());
      }
    }
    return spec.query((rs, n) -> map(rs, rs.getDouble("distance_km"))).list();
  }

  @Override
  public Map<UUID, List<Rule>> rules(Collection<UUID> ids) {
    Map<UUID, List<Rule>> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql(
            "SELECT listing_id, day_of_week, start_minute, end_minute FROM search_rules"
                + " WHERE listing_id IN (:ids)")
        .param("ids", List.copyOf(ids))
        .query((rs, n) -> {
          out.computeIfAbsent(rs.getObject("listing_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new Rule(
                      DayOfWeek.of(rs.getInt("day_of_week")),
                      rs.getInt("start_minute"),
                      rs.getInt("end_minute")));
          return null;
        })
        .list();
    return out;
  }

  @Override
  public Optional<Indexed> findVisible(UUID listingId) {
    return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE l.visible AND l.listing_id = :id")
        .param("id", listingId)
        .query((rs, n) -> map(rs, null))
        .optional();
  }

  // ---------------------------------------------------------------- projection writes

  @Override
  public boolean applySnapshot(Snapshot s, Instant at, Instant now) {
    Instant availabilityBefore =
        jdbc.sql(
                "SELECT availability_updated_at FROM search_listings WHERE listing_id = :id FOR UPDATE")
            .param("id", s.listingId())
            .query(OffsetDateTime.class)
            .optional()
            .map(OffsetDateTime::toInstant)
            .orElse(null);
    int rows =
        jdbc.sql(
                """
                INSERT INTO search_listings (listing_id, host_account_id, title, address, latitude,
                  longitude, time_zone, price_per_hour, currency, max_vehicle_type, max_vehicle_rank,
                  amenities, instant_book, cancellation_policy, buffer_minutes, thumbnail_url, visible,
                  source_updated_at, availability_updated_at, last_event_at)
                VALUES (:id, :host, :title, :address, :lat, :lng, :tz, :price, :currency, :vehicle,
                  :rank, CAST(:amenities AS text[]), :instant, :policy, :buffer, :thumb, :visible,
                  :at, :at, :now)
                ON CONFLICT (listing_id) DO UPDATE SET
                  title = EXCLUDED.title, address = EXCLUDED.address, latitude = EXCLUDED.latitude,
                  longitude = EXCLUDED.longitude, time_zone = EXCLUDED.time_zone,
                  price_per_hour = EXCLUDED.price_per_hour, currency = EXCLUDED.currency,
                  max_vehicle_type = EXCLUDED.max_vehicle_type,
                  max_vehicle_rank = EXCLUDED.max_vehicle_rank, amenities = EXCLUDED.amenities,
                  instant_book = EXCLUDED.instant_book,
                  cancellation_policy = EXCLUDED.cancellation_policy,
                  buffer_minutes = EXCLUDED.buffer_minutes, thumbnail_url = EXCLUDED.thumbnail_url,
                  visible = EXCLUDED.visible, source_updated_at = EXCLUDED.source_updated_at,
                  availability_updated_at = GREATEST(search_listings.availability_updated_at,
                    EXCLUDED.availability_updated_at),
                  last_event_at = EXCLUDED.last_event_at
                WHERE search_listings.source_updated_at <= EXCLUDED.source_updated_at
                """)
            .param("id", s.listingId())
            .param("host", s.hostAccountId())
            .param("title", s.title())
            .param("address", s.address())
            .param("lat", s.lat())
            .param("lng", s.lng())
            .param("tz", s.timeZone())
            .param("price", s.pricePerHour())
            .param("currency", s.currency())
            .param("vehicle", s.maxVehicleType())
            .param("rank", SearchQuery.vehicleRank(s.maxVehicleType()))
            .param("amenities", "{" + String.join(",", s.amenities()) + "}")
            .param("instant", s.instantBook())
            .param("policy", s.cancellationPolicy())
            .param("buffer", s.bufferMinutes())
            .param("thumb", s.thumbnailUrl())
            .param("visible", "PUBLISHED".equals(s.status()))
            .param("at", ts(at))
            .param("now", ts(now))
            .update();
    if (rows == 1 && (availabilityBefore == null || !at.isBefore(availabilityBefore))) {
      // A stale snapshot must not roll back newer rules/blackouts from AvailabilityChanged.
      replaceAvailability(s.listingId(), s.rules(), s.blackouts());
    }
    return rows == 1;
  }

  @Override
  public void applyAvailability(
      UUID listingId, List<Rule> rules, List<Window> blackouts, Instant at, Instant now) {
    int rows =
        jdbc.sql(
                """
                UPDATE search_listings SET availability_updated_at = :at, last_event_at = :now
                WHERE listing_id = :id AND availability_updated_at < :at
                """)
            .param("at", ts(at))
            .param("now", ts(now))
            .param("id", listingId)
            .update();
    if (rows == 1) {
      replaceAvailability(listingId, rules, blackouts);
    }
  }

  private void replaceAvailability(UUID listingId, List<Rule> rules, List<Window> blackouts) {
    jdbc.sql("DELETE FROM search_rules WHERE listing_id = :id").param("id", listingId).update();
    jdbc.sql("DELETE FROM search_blackouts WHERE listing_id = :id").param("id", listingId).update();
    for (Rule r : rules) {
      jdbc.sql(
              "INSERT INTO search_rules (listing_id, day_of_week, start_minute, end_minute)"
                  + " VALUES (:id, :d, :s, :e)")
          .param("id", listingId)
          .param("d", r.day().getValue())
          .param("s", r.startMinute())
          .param("e", r.endMinute())
          .update();
    }
    for (Window w : blackouts) {
      jdbc.sql("INSERT INTO search_blackouts (listing_id, start_at, end_at) VALUES (:id, :s, :e)")
          .param("id", listingId)
          .param("s", ts(w.start()))
          .param("e", ts(w.end()))
          .update();
    }
  }

  @Override
  public void hide(UUID listingId, Instant at, Instant now) {
    jdbc.sql(
            """
            UPDATE search_listings SET visible = false, source_updated_at = :at, last_event_at = :now
            WHERE listing_id = :id AND source_updated_at <= :at
            """)
        .param("at", ts(at))
        .param("now", ts(now))
        .param("id", listingId)
        .update();
  }

  @Override
  public void rating(UUID listingId, BigDecimal avg, int count, Instant at, Instant now) {
    jdbc.sql(
            """
            INSERT INTO search_ratings (listing_id, rating_avg, rating_count, updated_at)
            VALUES (:id, :avg, :count, :at)
            ON CONFLICT (listing_id) DO UPDATE SET rating_avg = EXCLUDED.rating_avg,
              rating_count = EXCLUDED.rating_count, updated_at = EXCLUDED.updated_at
            WHERE search_ratings.updated_at < EXCLUDED.updated_at
            """)
        .param("avg", avg)
        .param("count", count)
        .param("at", ts(at))
        .param("id", listingId)
        .update();
  }

  @Override
  public void busy(
      UUID bookingId, UUID listingId, Instant start, Instant blockedUntil, Instant now) {
    jdbc.sql(
            """
            INSERT INTO search_busy (booking_id, listing_id, start_at, blocked_until, updated_at)
            VALUES (:b, :l, :s, :u, :now)
            ON CONFLICT (booking_id) DO UPDATE SET
              listing_id = COALESCE(search_busy.listing_id, EXCLUDED.listing_id),
              start_at = COALESCE(search_busy.start_at, EXCLUDED.start_at),
              -- A late BookingRequested must not shrink the confirmed block (end + buffer).
              blocked_until = GREATEST(search_busy.blocked_until, EXCLUDED.blocked_until),
              updated_at = EXCLUDED.updated_at
            WHERE search_busy.ended_at IS NULL
            """)
        .param("b", bookingId)
        .param("l", listingId)
        .param("s", ts(start))
        .param("u", ts(blockedUntil))
        .param("now", ts(now))
        .update();
  }

  @Override
  public void free(UUID bookingId, Instant now) {
    jdbc.sql(
            """
            INSERT INTO search_busy (booking_id, ended_at, updated_at) VALUES (:b, :now, :now)
            ON CONFLICT (booking_id) DO UPDATE SET ended_at = EXCLUDED.ended_at,
              updated_at = EXCLUDED.updated_at
            WHERE search_busy.ended_at IS NULL
            """)
        .param("b", bookingId)
        .param("now", ts(now))
        .update();
  }

  /** Tombstones only need to outlive late duplicates; past bookings never matter to search. */
  @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
  @Transactional
  public void purge() {
    jdbc.sql(
            "DELETE FROM search_busy WHERE (ended_at IS NOT NULL AND ended_at < now() - interval '30 days')"
                + " OR blocked_until < now() - interval '30 days'")
        .update();
    jdbc.sql("DELETE FROM search_blackouts WHERE end_at < now() - interval '1 day'").update();
  }

  private static Indexed map(ResultSet rs, Double distance) throws SQLException {
    Array amenities = rs.getArray("amenities");
    return new Indexed(
        rs.getObject("listing_id", UUID.class),
        rs.getString("title"),
        rs.getString("address"),
        rs.getDouble("latitude"),
        rs.getDouble("longitude"),
        rs.getString("time_zone"),
        rs.getBigDecimal("price_per_hour"),
        rs.getString("currency"),
        rs.getString("max_vehicle_type"),
        amenities == null ? List.of() : Arrays.asList((String[]) amenities.getArray()),
        rs.getBoolean("instant_book"),
        rs.getString("cancellation_policy"),
        rs.getInt("buffer_minutes"),
        rs.getString("thumbnail_url"),
        rs.getBigDecimal("rating_avg"),
        rs.getInt("rating_count"),
        rs.getObject("last_event_at", OffsetDateTime.class).toInstant(),
        distance);
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
