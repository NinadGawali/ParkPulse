package com.parksmart.listing.infrastructure.persistence;

import com.parksmart.listing.application.port.Projections.ActiveBookingsProjection;
import com.parksmart.listing.application.port.Projections.HostEligibilityProjection;
import com.parksmart.listing.application.port.Projections.ModerationLog;
import com.parksmart.listing.application.port.Projections.PhotoUploads;
import com.parksmart.listing.domain.Values.HostEligibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Projections and housekeeping tables, in explicit SQL: idempotent upserts with "only newer wins"
 * guards are clearer here than in JPA. Runs in the caller's JPA transaction (same connection).
 */
@Repository
class JdbcProjections
    implements HostEligibilityProjection, ActiveBookingsProjection, ModerationLog, PhotoUploads {

  private final JdbcClient jdbc;

  JdbcProjections(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- host_eligibility

  @Override
  public HostEligibility find(UUID hostAccountId) {
    return jdbc.sql(
            """
            SELECT phone_verified, has_profile_photo, suspended FROM host_eligibility
            WHERE host_account_id = :id
            """)
        .param("id", hostAccountId)
        .query((rs, n) ->
            new HostEligibility(
                rs.getBoolean("phone_verified"),
                rs.getBoolean("has_profile_photo"),
                rs.getBoolean("suspended")))
        .optional()
        .orElse(HostEligibility.UNKNOWN);
  }

  @Override
  public void phoneVerified(UUID hostAccountId, boolean verified, Instant at) {
    jdbc.sql(
            """
            INSERT INTO host_eligibility (host_account_id, phone_verified, phone_changed_at, updated_at)
            VALUES (:id, :value, :at, now())
            ON CONFLICT (host_account_id) DO UPDATE
              SET phone_verified = EXCLUDED.phone_verified,
                  phone_changed_at = EXCLUDED.phone_changed_at,
                  updated_at = now()
              WHERE host_eligibility.phone_changed_at IS NULL
                 OR host_eligibility.phone_changed_at < EXCLUDED.phone_changed_at
            """)
        .param("id", hostAccountId)
        .param("value", verified)
        .param("at", ts(at))
        .update();
  }

  @Override
  public void profilePhoto(UUID hostAccountId, boolean hasPhoto, Instant at) {
    jdbc.sql(
            """
            INSERT INTO host_eligibility (host_account_id, has_profile_photo, photo_changed_at, updated_at)
            VALUES (:id, :value, :at, now())
            ON CONFLICT (host_account_id) DO UPDATE
              SET has_profile_photo = EXCLUDED.has_profile_photo,
                  photo_changed_at = EXCLUDED.photo_changed_at,
                  updated_at = now()
              WHERE host_eligibility.photo_changed_at IS NULL
                 OR host_eligibility.photo_changed_at < EXCLUDED.photo_changed_at
            """)
        .param("id", hostAccountId)
        .param("value", hasPhoto)
        .param("at", ts(at))
        .update();
  }

  @Override
  public void suspended(UUID hostAccountId, Instant at) {
    jdbc.sql(
            """
            INSERT INTO host_eligibility (host_account_id, suspended, suspended_at, updated_at)
            VALUES (:id, true, :at, now())
            ON CONFLICT (host_account_id) DO UPDATE
              SET suspended = true,
                  suspended_at = COALESCE(host_eligibility.suspended_at, EXCLUDED.suspended_at),
                  updated_at = now()
            """)
        .param("id", hostAccountId)
        .param("at", ts(at))
        .update();
  }

  // ---------------------------------------------------------------- listing_active_bookings

  @Override
  public void active(UUID bookingId, UUID listingId, Instant blockedUntil, Instant at) {
    jdbc.sql(
            """
            INSERT INTO listing_active_bookings (booking_id, listing_id, blocked_until, updated_at)
            VALUES (:booking, :listing, :until, now())
            ON CONFLICT (booking_id) DO UPDATE
              SET listing_id = COALESCE(listing_active_bookings.listing_id, EXCLUDED.listing_id),
                  blocked_until = COALESCE(EXCLUDED.blocked_until, listing_active_bookings.blocked_until),
                  updated_at = now()
              WHERE listing_active_bookings.ended_at IS NULL
            """)
        .param("booking", bookingId)
        .param("listing", listingId)
        .param("until", ts(blockedUntil))
        .update();
  }

  @Override
  public void ended(UUID bookingId, Instant at) {
    jdbc.sql(
            """
            INSERT INTO listing_active_bookings (booking_id, ended_at, updated_at)
            VALUES (:booking, :at, now())
            ON CONFLICT (booking_id) DO UPDATE
              SET ended_at = EXCLUDED.ended_at, updated_at = now()
              WHERE listing_active_bookings.ended_at IS NULL
            """)
        .param("booking", bookingId)
        .param("at", ts(at))
        .update();
  }

  @Override
  public boolean hasBlocking(UUID listingId, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM listing_active_bookings
                  WHERE listing_id = :listing AND ended_at IS NULL
                    AND (blocked_until IS NULL OR blocked_until > :now))
                """)
            .param("listing", listingId)
            .param("now", ts(now))
            .query(Boolean.class)
            .single());
  }

  // ---------------------------------------------------------------- moderation log

  @Override
  public void record(UUID listingId, UUID adminId, String decision, String reason, Instant at) {
    jdbc.sql(
            """
            INSERT INTO listing_moderation_log (id, listing_id, reviewed_by, decision, reason, reviewed_at)
            VALUES (:id, :listing, :admin, :decision, :reason, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("listing", listingId)
        .param("admin", adminId)
        .param("decision", decision)
        .param("reason", reason)
        .param("at", ts(at))
        .update();
  }

  @Override
  public Optional<String> latestRejectionReason(UUID listingId) {
    return jdbc.sql(
            """
            SELECT reason FROM listing_moderation_log
            WHERE listing_id = :listing AND decision = 'REJECTED'
            ORDER BY reviewed_at DESC, id DESC LIMIT 1
            """)
        .param("listing", listingId)
        .query(String.class)
        .optional();
  }

  // ---------------------------------------------------------------- photo upload housekeeping

  @Override
  public List<StaleUpload> expiredPending(Instant cutoff, int limit) {
    return stale("status = 'PENDING_UPLOAD'", cutoff, limit);
  }

  @Override
  public List<StaleUpload> staleStaging(Instant cutoff, int limit) {
    return stale("status = 'READY' AND staging_key IS NOT NULL", cutoff, limit);
  }

  private List<StaleUpload> stale(String condition, Instant cutoff, int limit) {
    return jdbc.sql(
            "SELECT id, staging_key FROM listing_photos WHERE "
                + condition
                + " AND upload_expires_at < :cutoff ORDER BY upload_expires_at"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("cutoff", ts(cutoff))
        .param("limit", limit)
        .query(
            (rs, n) -> new StaleUpload(rs.getObject("id", UUID.class), rs.getString("staging_key")))
        .list();
  }

  @Override
  public void deletePending(UUID photoId) {
    jdbc.sql("DELETE FROM listing_photos WHERE id = :id AND status = 'PENDING_UPLOAD'")
        .param("id", photoId)
        .update();
  }

  @Override
  public void clearStaging(UUID photoId) {
    jdbc.sql("UPDATE listing_photos SET staging_key = NULL WHERE id = :id")
        .param("id", photoId)
        .update();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
