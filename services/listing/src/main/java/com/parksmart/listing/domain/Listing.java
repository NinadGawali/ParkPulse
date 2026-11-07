package com.parksmart.listing.domain;

import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Enums.PhotoStatus;
import com.parksmart.listing.domain.Enums.UnbookableReason;
import com.parksmart.listing.domain.Values.Blackout;
import com.parksmart.listing.domain.Values.GeoPoint;
import com.parksmart.listing.domain.Values.HostEligibility;
import com.parksmart.listing.domain.Values.PriceBounds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A parking listing and everything the host edits with it (photos, weekly rules, blackouts).
 *
 * <p>Lifecycle: DRAFT -> PENDING_REVIEW -> PUBLISHED <-> PAUSED; PENDING_REVIEW -> REJECTED ->
 * PENDING_REVIEW; anything but ARCHIVED -> ARCHIVED. Editing the address or location of a
 * PUBLISHED/PAUSED listing sends it back to PENDING_REVIEW (approval then publishes it, also if it
 * was paused before).
 */
public final class Listing {

  public static final int MAX_PHOTOS = 10;
  public static final int MAX_RULES = 21;
  public static final int MAX_BLACKOUTS = 100;
  public static final int MAX_AMENITIES = 8;
  public static final Duration MAX_QUOTE_WINDOW = Duration.ofDays(31);

  /** Blackouts may start at most this far ahead. */
  public static final Duration MAX_BLACKOUT_LEAD = Duration.ofDays(2 * 366);

  /** Ended blackouts are dropped this long after they end. */
  public static final Duration BLACKOUT_RETENTION = Duration.ofDays(30);

  public static final String CURRENCY = "INR";

  /** What an update changed, which decides the events it produces. */
  public enum Change {
    NONE,
    /** Public fields changed; the listing kept its status. */
    PUBLIC,
    /** Address or location of a live listing changed; it is back in moderation. */
    RELOCATED
  }

  private final UUID id;
  private final UUID hostAccountId;
  private String title;
  private String description;
  private String address;
  private GeoPoint location;
  private ZoneId timeZone;
  private BigDecimal pricePerHour;
  private String currency;
  private VehicleType maxVehicleType;
  private final EnumSet<Amenity> amenities = EnumSet.noneOf(Amenity.class);
  private boolean instantBook;
  private CancellationPolicy cancellationPolicy;
  private int bufferMinutes;
  private ListingStatus status;
  private final Instant createdAt;
  private Instant updatedAt;
  private Instant deletedAt;
  private final long version;
  private final List<ListingPhoto> photos;
  private final List<AvailabilityRule> rules;
  private final List<Blackout> blackouts;

  private Listing(
      UUID id,
      UUID hostAccountId,
      ListingStatus status,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version,
      List<ListingPhoto> photos,
      List<AvailabilityRule> rules,
      List<Blackout> blackouts) {
    this.id = Objects.requireNonNull(id, "id");
    this.hostAccountId = Objects.requireNonNull(hostAccountId, "hostAccountId");
    this.status = Objects.requireNonNull(status, "status");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.deletedAt = deletedAt;
    this.version = version;
    this.photos = new ArrayList<>(photos);
    this.rules = new ArrayList<>(rules);
    this.rules.sort(
        Comparator.comparing(AvailabilityRule::day)
            .thenComparingInt(AvailabilityRule::startMinute));
    this.blackouts = new ArrayList<>(blackouts);
  }

  /** A new DRAFT. Every term except description and amenities is required. */
  public static Listing create(
      UUID id, UUID hostAccountId, ListingTerms terms, PriceBounds bounds, Instant now) {
    List<String> missing = new ArrayList<>();
    if (terms.title() == null) missing.add("title");
    if (terms.address() == null) missing.add("address");
    if (terms.location() == null) missing.add("location");
    if (terms.timeZone() == null) missing.add("timeZone");
    if (terms.pricePerHour() == null) missing.add("pricePerHour");
    if (terms.currency() == null) missing.add("currency");
    if (terms.maxVehicleType() == null) missing.add("maxVehicleType");
    if (terms.instantBook() == null) missing.add("instantBook");
    if (terms.cancellationPolicy() == null) missing.add("cancellationPolicy");
    if (terms.bufferMinutes() == null) missing.add("bufferMinutes");
    if (!missing.isEmpty()) {
      throw new DomainException("invalid-listing", "Missing required fields: " + missing);
    }
    Listing listing =
        new Listing(
            id,
            hostAccountId,
            ListingStatus.DRAFT,
            now,
            now,
            null,
            -1,
            List.of(),
            List.of(),
            List.of());
    listing.apply(terms, bounds);
    return listing;
  }

  /** Rebuilds a listing from storage (no validation: stored data was valid when written). */
  public static Listing restore(
      UUID id,
      UUID hostAccountId,
      String title,
      String description,
      String address,
      GeoPoint location,
      ZoneId timeZone,
      BigDecimal pricePerHour,
      String currency,
      VehicleType maxVehicleType,
      Set<Amenity> amenities,
      boolean instantBook,
      CancellationPolicy cancellationPolicy,
      int bufferMinutes,
      ListingStatus status,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version,
      List<ListingPhoto> photos,
      List<AvailabilityRule> rules,
      List<Blackout> blackouts) {
    Listing l =
        new Listing(
            id,
            hostAccountId,
            status,
            createdAt,
            updatedAt,
            deletedAt,
            version,
            photos,
            rules,
            blackouts);
    l.title = title;
    l.description = description;
    l.address = address;
    l.location = location;
    l.timeZone = timeZone;
    l.pricePerHour = pricePerHour;
    l.currency = currency;
    l.maxVehicleType = maxVehicleType;
    l.amenities.addAll(amenities);
    l.instantBook = instantBook;
    l.cancellationPolicy = cancellationPolicy;
    l.bufferMinutes = bufferMinutes;
    return l;
  }

  // ---------------------------------------------------------------- terms

  /** Partial update. Not allowed while PENDING_REVIEW or ARCHIVED. */
  public Change update(ListingTerms patch, PriceBounds bounds, Instant now) {
    ensureNotArchived();
    if (status == ListingStatus.PENDING_REVIEW) {
      throw new DomainException(
          "listing-not-editable", "A listing cannot be edited while it is being reviewed");
    }
    String oldAddress = address;
    GeoPoint oldLocation = location;
    boolean changed = apply(patch, bounds);
    if (!changed) {
      return Change.NONE;
    }
    touch(now);
    boolean live = status == ListingStatus.PUBLISHED || status == ListingStatus.PAUSED;
    if (live && (!address.equals(oldAddress) || !location.equals(oldLocation))) {
      status = ListingStatus.PENDING_REVIEW;
      return Change.RELOCATED;
    }
    return Change.PUBLIC;
  }

  /** Validates and applies the non-null fields. Returns true if anything changed. */
  private boolean apply(ListingTerms t, PriceBounds bounds) {
    boolean changed = false;
    if (t.title() != null) {
      String v = Text.clean(t.title(), "title", 5, 255, true, false);
      changed |= !v.equals(title);
      title = v;
    }
    if (t.description() != null) {
      String v = Text.clean(t.description(), "description", 1, 5000, false, true);
      changed |= !Objects.equals(v, description);
      description = v;
    }
    if (t.address() != null) {
      String v = Text.clean(t.address(), "address", 5, 512, true, false);
      changed |= !v.equals(address);
      address = v;
    }
    if (t.location() != null) {
      changed |= !t.location().equals(location);
      location = t.location();
    }
    if (t.timeZone() != null) {
      ZoneId zone = zone(t.timeZone());
      changed |= !zone.equals(timeZone);
      timeZone = zone;
    }
    if (t.pricePerHour() != null) {
      bounds.check(t.pricePerHour());
      BigDecimal v = t.pricePerHour().setScale(2);
      changed |= pricePerHour == null || v.compareTo(pricePerHour) != 0;
      pricePerHour = v;
    }
    if (t.currency() != null) {
      if (!CURRENCY.equals(t.currency())) {
        throw new DomainException("invalid-currency", "Only INR is supported");
      }
      changed |= !t.currency().equals(currency);
      currency = t.currency();
    }
    if (t.maxVehicleType() != null) {
      changed |= t.maxVehicleType() != maxVehicleType;
      maxVehicleType = t.maxVehicleType();
    }
    if (t.amenities() != null) {
      if (t.amenities().size() > MAX_AMENITIES) {
        throw new DomainException("invalid-amenities", "At most " + MAX_AMENITIES + " amenities");
      }
      changed |=
          !amenities.equals(
              t.amenities().isEmpty()
                  ? EnumSet.noneOf(Amenity.class)
                  : EnumSet.copyOf(t.amenities()));
      amenities.clear();
      amenities.addAll(t.amenities());
    }
    if (t.instantBook() != null) {
      changed |= t.instantBook() != instantBook;
      instantBook = t.instantBook();
    }
    if (t.cancellationPolicy() != null) {
      changed |= t.cancellationPolicy() != cancellationPolicy;
      cancellationPolicy = t.cancellationPolicy();
    }
    if (t.bufferMinutes() != null) {
      int b = t.bufferMinutes();
      if (b < 0 || b > 120 || b % 5 != 0) {
        throw new DomainException(
            "invalid-bufferMinutes", "bufferMinutes must be 0-120 in steps of 5");
      }
      changed |= b != bufferMinutes;
      bufferMinutes = b;
    }
    return changed;
  }

  private static ZoneId zone(String name) {
    if (!ZoneId.getAvailableZoneIds().contains(name)) {
      throw new DomainException(
          "invalid-timeZone", "timeZone must be an IANA zone, e.g. Asia/Kolkata");
    }
    return ZoneId.of(name);
  }

  // ---------------------------------------------------------------- lifecycle

  /** DRAFT or REJECTED -> PENDING_REVIEW, if every publishing requirement is met. */
  public void submit(boolean callerIsHost, HostEligibility host, Instant now) {
    requireStatus("submit", ListingStatus.DRAFT, ListingStatus.REJECTED);
    List<String> unmet = new ArrayList<>();
    if (photos.stream().noneMatch(ListingPhoto::isReady)) unmet.add("at least one photo");
    if (rules.isEmpty()) unmet.add("at least one availability rule");
    if (!callerIsHost) unmet.add("HOST role");
    if (!host.phoneVerified()) unmet.add("a verified phone number");
    if (!host.hasProfilePhoto()) unmet.add("a profile photo");
    if (host.suspended()) unmet.add("an account in good standing");
    if (!unmet.isEmpty()) {
      throw new DomainException(
          "listing-not-submittable", "Before submitting, add: " + String.join(", ", unmet));
    }
    status = ListingStatus.PENDING_REVIEW;
    touch(now);
  }

  /** Admin: PENDING_REVIEW -> PUBLISHED. A suspended host's listing cannot go live. */
  public void approve(HostEligibility host, Instant now) {
    requireStatus("approve", ListingStatus.PENDING_REVIEW);
    if (host.suspended()) {
      throw new DomainException("host-suspended", "The host's account is suspended");
    }
    status = ListingStatus.PUBLISHED;
    touch(now);
  }

  /** Admin: PENDING_REVIEW -> REJECTED. */
  public String reject(String reason, Instant now) {
    requireStatus("reject", ListingStatus.PENDING_REVIEW);
    String clean = Text.clean(reason, "reason", 1, 500, true, true);
    status = ListingStatus.REJECTED;
    touch(now);
    return clean;
  }

  /** An optional moderation note, cleaned like any other text. */
  public static String cleanNote(String note) {
    return Text.clean(note, "reason", 1, 500, false, true);
  }

  public void pause(Instant now) {
    requireStatus("pause", ListingStatus.PUBLISHED);
    status = ListingStatus.PAUSED;
    touch(now);
  }

  public void resume(HostEligibility host, Instant now) {
    requireStatus("resume", ListingStatus.PAUSED);
    if (host.suspended()) {
      throw new DomainException("host-suspended", "The host's account is suspended");
    }
    status = ListingStatus.PUBLISHED;
    touch(now);
  }

  /** Soft delete. The caller has already checked for active bookings. */
  public void archive(Instant now) {
    ensureNotArchived();
    status = ListingStatus.ARCHIVED;
    deletedAt = now;
    touch(now);
  }

  /** The host was suspended: a PUBLISHED listing stops taking bookings. Returns true if paused. */
  public boolean pauseForSuspension(Instant now) {
    if (status != ListingStatus.PUBLISHED) {
      return false;
    }
    status = ListingStatus.PAUSED;
    touch(now);
    return true;
  }

  // ---------------------------------------------------------------- photos

  /** Reserves a photo slot; the client uploads to the returned photo's staging key. */
  public ListingPhoto startPhotoUpload(
      UUID photoId,
      PhotoContentType type,
      long contentLength,
      long maxBytes,
      Instant now,
      Duration ttl) {
    ensurePhotosEditable();
    if (contentLength < 1 || contentLength > maxBytes) {
      throw new DomainException("invalid-contentLength", "contentLength must be 1-" + maxBytes);
    }
    if (photos.size() >= MAX_PHOTOS) {
      throw new DomainException(
          "photo-limit", "At most " + MAX_PHOTOS + " photos (including unconfirmed uploads)");
    }
    ListingPhoto photo =
        new ListingPhoto(
            photoId,
            PhotoStatus.PENDING_UPLOAD,
            null,
            null,
            "uploads/listings/" + id + "/" + photoId,
            type,
            contentLength,
            0,
            now.plus(ttl),
            now);
    photos.add(photo);
    touch(now);
    return photo;
  }

  /** The public, server-owned key a confirmed photo is copied to. */
  public String publicPhotoKey(UUID photoId) {
    return "listings/" + id + "/photo-" + photoId;
  }

  /** Checks that a pending photo can be confirmed now (the caller then copies and verifies it). */
  public ListingPhoto pendingPhoto(UUID photoId, Instant now) {
    ensurePhotosEditable();
    ListingPhoto photo = photo(photoId);
    if (photo.isReady()) {
      throw new DomainException("photo-already-confirmed", "Photo was already confirmed");
    }
    if (!now.isBefore(photo.uploadExpiresAt())) {
      throw new DomainException("upload-expired", "Upload slot has expired; request a new one");
    }
    return photo;
  }

  /** Marks a pending photo READY at the end of the order. */
  public ListingPhoto confirmPhoto(UUID photoId, String publicKey, String url, Instant now) {
    ListingPhoto photo = pendingPhoto(photoId, now);
    int next = readyPhotos().stream().mapToInt(ListingPhoto::sortOrder).max().orElse(-1) + 1;
    photo.markReady(publicKey, url, next);
    touch(now);
    return photo;
  }

  /** Removes a photo. The last READY photo of a live or in-review listing cannot be removed. */
  public ListingPhoto removePhoto(UUID photoId, Instant now) {
    ensurePhotosEditable();
    ListingPhoto photo = photo(photoId);
    boolean needsPhoto =
        status == ListingStatus.PUBLISHED || status == ListingStatus.PENDING_REVIEW;
    if (photo.isReady() && needsPhoto && readyPhotos().size() == 1) {
      throw new DomainException("last-photo", "A published listing needs at least one photo");
    }
    photos.remove(photo);
    touch(now);
    return photo;
  }

  /** {@code order} must list every READY photo exactly once. */
  public void reorderPhotos(List<UUID> order, Instant now) {
    ensurePhotosEditable();
    List<ListingPhoto> ready = readyPhotos();
    Set<UUID> readyIds = new HashSet<>();
    ready.forEach(p -> readyIds.add(p.id()));
    if (order.size() != readyIds.size() || !readyIds.equals(new HashSet<>(order))) {
      throw new DomainException(
          "invalid-photo-order", "photoIds must list every confirmed photo exactly once");
    }
    for (int i = 0; i < order.size(); i++) {
      photo(order.get(i)).setSortOrder(i);
    }
    touch(now);
  }

  private ListingPhoto photo(UUID photoId) {
    return photos.stream()
        .filter(p -> p.id().equals(photoId))
        .findFirst()
        .orElseThrow(() -> new DomainException("photo-not-found", "Photo not found"));
  }

  public List<ListingPhoto> readyPhotos() {
    return photos.stream()
        .filter(ListingPhoto::isReady)
        .sorted(Comparator.comparingInt(ListingPhoto::sortOrder))
        .toList();
  }

  public Optional<String> thumbnailUrl() {
    return readyPhotos().stream().findFirst().map(ListingPhoto::url);
  }

  // ---------------------------------------------------------------- availability

  /** Replaces the weekly schedule. Rules on the same day must not overlap. */
  public void replaceRules(List<AvailabilityRule> newRules, Instant now) {
    ensureNotArchived();
    if (newRules.size() > MAX_RULES) {
      throw new DomainException("invalid-availability", "At most " + MAX_RULES + " rules");
    }
    List<AvailabilityRule> sorted =
        newRules.stream()
            .sorted(
                Comparator.comparing(AvailabilityRule::day)
                    .thenComparingInt(AvailabilityRule::startMinute))
            .toList();
    for (int i = 1; i < sorted.size(); i++) {
      AvailabilityRule prev = sorted.get(i - 1);
      AvailabilityRule next = sorted.get(i);
      if (prev.day() == next.day() && next.startMinute() < prev.endMinute()) {
        throw new DomainException("invalid-availability", "Rules on " + next.day() + " overlap");
      }
    }
    rules.clear();
    rules.addAll(sorted);
    touch(now);
  }

  public void addBlackout(Blackout blackout, Instant now) {
    ensureNotArchived();
    if (!blackout.end().isAfter(now)) {
      throw new DomainException("invalid-blackout", "A blackout must end in the future");
    }
    if (Duration.between(blackout.start(), blackout.end()).compareTo(Duration.ofDays(366)) > 0) {
      throw new DomainException("invalid-blackout", "A blackout can span at most 366 days");
    }
    if (blackout.start().isAfter(now.plus(MAX_BLACKOUT_LEAD))) {
      throw new DomainException("invalid-blackout", "A blackout can start at most 2 years ahead");
    }
    blackouts.removeIf(b -> b.end().isBefore(now.minus(BLACKOUT_RETENTION)));
    long current = blackouts.stream().filter(b -> b.end().isAfter(now)).count();
    if (current >= MAX_BLACKOUTS) {
      throw new DomainException("blackout-limit", "At most " + MAX_BLACKOUTS + " blackouts");
    }
    blackouts.add(blackout);
    touch(now);
  }

  public void removeBlackout(UUID blackoutId, Instant now) {
    ensureNotArchived();
    if (!blackouts.removeIf(b -> b.id().equals(blackoutId))) {
      throw new DomainException("blackout-not-found", "Blackout not found");
    }
    touch(now);
  }

  /**
   * Whether {@code [start, end)} can be booked: PUBLISHED, no blackout overlaps, and every minute
   * is inside a weekly rule (in the listing's time zone; adjacent rules join, also across
   * midnight). Overlap with other bookings is Booking's job.
   */
  public Optional<UnbookableReason> bookability(Instant start, Instant end) {
    if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_QUOTE_WINDOW) > 0) {
      throw new DomainException(
          "invalid-window", "start must be before end and the window at most 31 days");
    }
    if (status != ListingStatus.PUBLISHED) {
      return Optional.of(UnbookableReason.NOT_PUBLISHED);
    }
    if (blackouts.stream().anyMatch(b -> b.overlaps(start, end))) {
      return Optional.of(UnbookableReason.BLACKED_OUT);
    }
    return covered(start, end)
        ? Optional.empty()
        : Optional.of(UnbookableReason.OUTSIDE_AVAILABILITY);
  }

  private boolean covered(Instant start, Instant end) {
    LocalDateTime from = LocalDateTime.ofInstant(start, timeZone);
    LocalDateTime to = LocalDateTime.ofInstant(end, timeZone);
    if (!to.isAfter(from)) {
      // DST fall-back: the window ends in the repeated hour at a wall-clock time at or before
      // its start. Require the whole wall-clock span the window touches.
      LocalDateTime swap = from;
      from = to;
      to = swap.plusMinutes(1);
    }
    for (LocalDate day = from.toLocalDate();
        !day.isAfter(to.toLocalDate());
        day = day.plusDays(1)) {
      int segStart = day.equals(from.toLocalDate()) ? minuteFloor(from) : 0;
      int segEnd = day.equals(to.toLocalDate()) ? minuteCeil(to) : 1440;
      if (segStart >= segEnd) {
        continue;
      }
      if (!dayCovers(day, segStart, segEnd)) {
        return false;
      }
    }
    return true;
  }

  private boolean dayCovers(LocalDate day, int from, int to) {
    int reached = from;
    for (AvailabilityRule rule : rules) { // sorted by day, then start
      if (rule.day() != day.getDayOfWeek()) {
        continue;
      }
      if (rule.startMinute() > reached) {
        return false;
      }
      reached = Math.max(reached, rule.endMinute());
      if (reached >= to) {
        return true;
      }
    }
    return reached >= to;
  }

  private static int minuteFloor(LocalDateTime t) {
    return t.getHour() * 60 + t.getMinute();
  }

  private static int minuteCeil(LocalDateTime t) {
    return minuteFloor(t) + (t.getSecond() > 0 || t.getNano() > 0 ? 1 : 0);
  }

  // ---------------------------------------------------------------- helpers

  public boolean isOwnedBy(UUID accountId) {
    return hostAccountId.equals(accountId);
  }

  /** Visible to everyone only when PUBLISHED. */
  public boolean isPublic() {
    return status == ListingStatus.PUBLISHED;
  }

  /** PUBLISHED or PAUSED: Search knows about it, so changes must be announced. */
  public boolean isLive() {
    return status == ListingStatus.PUBLISHED || status == ListingStatus.PAUSED;
  }

  private void requireStatus(String action, ListingStatus... allowed) {
    for (ListingStatus s : allowed) {
      if (status == s) {
        return;
      }
    }
    throw new DomainException(
        "illegal-transition", "Cannot " + action + " a listing that is " + status);
  }

  /** What the moderator reviews must not change under them. */
  private void ensurePhotosEditable() {
    ensureNotArchived();
    if (status == ListingStatus.PENDING_REVIEW) {
      throw new DomainException(
          "listing-not-editable", "Photos cannot change while the listing is being reviewed");
    }
  }

  private void ensureNotArchived() {
    if (status == ListingStatus.ARCHIVED) {
      throw new DomainException("listing-archived", "The listing is archived");
    }
  }

  /** updatedAt only moves forward. */
  private void touch(Instant now) {
    if (now.isAfter(updatedAt)) {
      updatedAt = now;
    }
  }

  public boolean isNew() {
    return version < 0;
  }

  public UUID id() {
    return id;
  }

  public UUID hostAccountId() {
    return hostAccountId;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public String address() {
    return address;
  }

  public GeoPoint location() {
    return location;
  }

  public ZoneId timeZone() {
    return timeZone;
  }

  public BigDecimal pricePerHour() {
    return pricePerHour;
  }

  public String currency() {
    return currency;
  }

  public VehicleType maxVehicleType() {
    return maxVehicleType;
  }

  public Set<Amenity> amenities() {
    return Collections.unmodifiableSet(amenities);
  }

  public boolean instantBook() {
    return instantBook;
  }

  public CancellationPolicy cancellationPolicy() {
    return cancellationPolicy;
  }

  public int bufferMinutes() {
    return bufferMinutes;
  }

  public ListingStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  public long version() {
    return version;
  }

  public List<ListingPhoto> photos() {
    return Collections.unmodifiableList(photos);
  }

  public List<AvailabilityRule> rules() {
    return Collections.unmodifiableList(rules);
  }

  public List<Blackout> blackouts() {
    return Collections.unmodifiableList(blackouts);
  }
}
