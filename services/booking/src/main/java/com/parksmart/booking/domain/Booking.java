package com.parksmart.booking.domain;

import static com.parksmart.booking.domain.BookingStatus.AWAITING_HOST_APPROVAL;
import static com.parksmart.booking.domain.BookingStatus.CANCELLED_BY_DRIVER;
import static com.parksmart.booking.domain.BookingStatus.CANCELLED_BY_HOST;
import static com.parksmart.booking.domain.BookingStatus.CHECKED_IN;
import static com.parksmart.booking.domain.BookingStatus.COMPLETED;
import static com.parksmart.booking.domain.BookingStatus.CONFIRMED;
import static com.parksmart.booking.domain.BookingStatus.DECLINED_BY_HOST;
import static com.parksmart.booking.domain.BookingStatus.EXPIRED;
import static com.parksmart.booking.domain.BookingStatus.NO_SHOW;
import static com.parksmart.booking.domain.BookingStatus.PAYMENT_FAILED;
import static com.parksmart.booking.domain.BookingStatus.PENDING_PAYMENT;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A booking and its state machine (booking.yaml). Every transition validates the current state and
 * returns the state it moved from; the repository applies it with a conditional update ({@code
 * WHERE status = from AND version = v}), so concurrent actors cannot both win.
 */
public final class Booking {

  public static final Duration GRANULARITY = Duration.ofMinutes(15);
  public static final Duration MIN_DURATION = Duration.ofMinutes(30);
  public static final Duration MAX_DURATION = Duration.ofDays(30);
  public static final Duration MIN_LEAD = Duration.ofMinutes(15);
  public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);
  public static final Duration HOST_DECISION_WINDOW = Duration.ofHours(24);
  public static final Duration CHECK_IN_OPENS = Duration.ofMinutes(15);
  public static final Duration CHECK_IN_CLOSES = Duration.ofMinutes(30);
  public static final Duration REVIEW_WINDOW = Duration.ofDays(14);

  /** Bookings may start at most this far ahead. */
  public static final Duration MAX_LEAD = Duration.ofDays(365);

  /** Unpaid bookings a driver may hold at once (each holds a slot for 10 minutes). */
  public static final int MAX_PENDING_PER_DRIVER = 3;

  /** The listing terms Booking snapshots at creation (from Listing's booking quote). */
  public record Terms(
      UUID hostAccountId,
      BigDecimal pricePerHour,
      String currency,
      boolean instantBook,
      CancellationPolicy cancellationPolicy,
      int bufferMinutes) {

    public Terms {
      Objects.requireNonNull(hostAccountId, "hostAccountId");
      Objects.requireNonNull(pricePerHour, "pricePerHour");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(cancellationPolicy, "cancellationPolicy");
      if (pricePerHour.signum() <= 0
          || pricePerHour.scale() > 2
          || pricePerHour.compareTo(new BigDecimal("100000")) >= 0
          || !"INR".equals(currency)
          || bufferMinutes < 0
          || bufferMinutes > 120) {
        throw new IllegalArgumentException("Listing terms are out of range");
      }
    }
  }

  private final UUID id;
  private final UUID driverAccountId;
  private final UUID listingId;
  private final UUID vehicleId;
  private final Instant startTime;
  private final Instant endTime;
  private final Terms terms;
  private final BigDecimal totalAmount;
  private BookingStatus status;
  private Instant paymentDueBy;
  private Instant hostDecisionDueBy;
  private BigDecimal refundAmount;
  private final String idempotencyKey;
  private final String requestHash;
  private final Instant createdAt;
  private Instant updatedAt;
  private final int version;

  private Booking(
      UUID id,
      UUID driverAccountId,
      UUID listingId,
      UUID vehicleId,
      Instant startTime,
      Instant endTime,
      Terms terms,
      BigDecimal totalAmount,
      BookingStatus status,
      Instant paymentDueBy,
      Instant hostDecisionDueBy,
      BigDecimal refundAmount,
      String idempotencyKey,
      String requestHash,
      Instant createdAt,
      Instant updatedAt,
      int version) {
    this.id = Objects.requireNonNull(id, "id");
    this.driverAccountId = Objects.requireNonNull(driverAccountId, "driverAccountId");
    this.listingId = Objects.requireNonNull(listingId, "listingId");
    this.vehicleId = vehicleId;
    this.startTime = Objects.requireNonNull(startTime, "startTime");
    this.endTime = Objects.requireNonNull(endTime, "endTime");
    this.terms = Objects.requireNonNull(terms, "terms");
    this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount");
    this.status = Objects.requireNonNull(status, "status");
    this.paymentDueBy = paymentDueBy;
    this.hostDecisionDueBy = hostDecisionDueBy;
    this.refundAmount = refundAmount;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.version = version;
  }

  /** Checks the booking rules that do not depend on the listing. */
  public static void validateWindow(Instant start, Instant end, Instant now) {
    if (start == null || end == null || !start.isBefore(end)) {
      throw invalidWindow("startTime must be before endTime");
    }
    if (!onGrid(start) || !onGrid(end)) {
      throw invalidWindow("startTime and endTime must be on 15-minute boundaries");
    }
    if (start.isBefore(now.plus(MIN_LEAD))) {
      throw invalidWindow("startTime must be at least 15 minutes from now");
    }
    if (start.isAfter(now.plus(MAX_LEAD))) {
      throw invalidWindow("startTime can be at most 1 year ahead");
    }
    Duration length = Duration.between(start, end);
    if (length.compareTo(MIN_DURATION) < 0 || length.compareTo(MAX_DURATION) > 0) {
      throw invalidWindow("A booking lasts 30 minutes to 30 days");
    }
  }

  /** A new booking holding the slot in PENDING_PAYMENT. */
  public static Booking request(
      UUID id,
      UUID driverAccountId,
      UUID listingId,
      UUID vehicleId,
      Instant start,
      Instant end,
      Terms terms,
      String idempotencyKey,
      String requestHash,
      Instant now) {
    validateWindow(start, end, now);
    if (terms.hostAccountId().equals(driverAccountId)) {
      throw new DomainException("cannot-book-own-listing", "You cannot book your own listing");
    }
    return new Booking(
        id,
        driverAccountId,
        listingId,
        vehicleId,
        start,
        end,
        terms,
        total(terms.pricePerHour(), start, end),
        PENDING_PAYMENT,
        now.plus(PAYMENT_WINDOW),
        null,
        null,
        idempotencyKey,
        requestHash,
        now,
        now,
        0);
  }

  public static Booking restore(
      UUID id,
      UUID driverAccountId,
      UUID listingId,
      UUID vehicleId,
      Instant startTime,
      Instant endTime,
      Terms terms,
      BigDecimal totalAmount,
      BookingStatus status,
      Instant paymentDueBy,
      Instant hostDecisionDueBy,
      BigDecimal refundAmount,
      String idempotencyKey,
      String requestHash,
      Instant createdAt,
      Instant updatedAt,
      int version) {
    return new Booking(
        id,
        driverAccountId,
        listingId,
        vehicleId,
        startTime,
        endTime,
        terms,
        totalAmount,
        status,
        paymentDueBy,
        hostDecisionDueBy,
        refundAmount,
        idempotencyKey,
        requestHash,
        createdAt,
        updatedAt,
        version);
  }

  /** pricePerHour x hours, HALF_UP to 2 decimals. */
  static BigDecimal total(BigDecimal pricePerHour, Instant start, Instant end) {
    BigDecimal minutes = BigDecimal.valueOf(Duration.between(start, end).toMinutes());
    return pricePerHour.multiply(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
  }

  private static boolean onGrid(Instant t) {
    return t.getEpochSecond() % GRANULARITY.toSeconds() == 0 && t.getNano() == 0;
  }

  private static DomainException invalidWindow(String message) {
    return new DomainException("invalid-booking-window", message);
  }

  // ---------------------------------------------------------------- saga (events)

  /** Payment authorized: CONFIRMED (instant book) or AWAITING_HOST_APPROVAL. */
  public BookingStatus paymentAuthorized(Instant now) {
    BookingStatus from = move(EnumSet.of(PENDING_PAYMENT), now);
    paymentDueBy = null;
    if (terms.instantBook()) {
      status = CONFIRMED;
    } else {
      status = AWAITING_HOST_APPROVAL;
      Instant dayLater = createdAt.plus(HOST_DECISION_WINDOW);
      hostDecisionDueBy = dayLater.isBefore(startTime) ? dayLater : startTime;
    }
    return from;
  }

  /** Payment declined: the slot is freed. */
  public BookingStatus paymentFailed(Instant now) {
    BookingStatus from = move(EnumSet.of(PENDING_PAYMENT), now);
    paymentDueBy = null;
    status = EXPIRED;
    return from;
  }

  /** Capture was rejected after confirmation: the slot is freed, both parties are told. */
  public BookingStatus captureFailed(Instant now) {
    BookingStatus from = move(EnumSet.of(CONFIRMED, CHECKED_IN), now);
    status = PAYMENT_FAILED;
    return from;
  }

  /**
   * The listing was archived (a race with a booking request). Bookings that have not started are
   * ended; refunds are 100% because the host withdrew the space.
   */
  public BookingStatus listingWithdrawn(Instant now) {
    if (status == PENDING_PAYMENT) {
      BookingStatus from = move(EnumSet.of(PENDING_PAYMENT), now);
      paymentDueBy = null;
      status = EXPIRED;
      return from;
    }
    if (!now.isBefore(startTime)) {
      throw illegal("withdraw a booking that has started");
    }
    BookingStatus from = move(EnumSet.of(AWAITING_HOST_APPROVAL, CONFIRMED), now);
    hostDecisionDueBy = null;
    refundAmount = totalAmount;
    status = CANCELLED_BY_HOST;
    return from;
  }

  // ---------------------------------------------------------------- timers (jobs)

  public BookingStatus expirePayment(Instant now) {
    if (status == PENDING_PAYMENT && now.isBefore(paymentDueBy)) {
      throw illegal("expire a booking before its payment deadline");
    }
    BookingStatus from = move(EnumSet.of(PENDING_PAYMENT), now);
    paymentDueBy = null;
    status = EXPIRED;
    return from;
  }

  public BookingStatus expireHostDecision(Instant now) {
    if (status == AWAITING_HOST_APPROVAL && now.isBefore(hostDecisionDueBy)) {
      throw illegal("expire a request before the host's deadline");
    }
    BookingStatus from = move(EnumSet.of(AWAITING_HOST_APPROVAL), now);
    hostDecisionDueBy = null;
    status = EXPIRED;
    return from;
  }

  public BookingStatus markNoShow(Instant now) {
    if (status == CONFIRMED && now.isBefore(startTime.plus(CHECK_IN_CLOSES))) {
      throw illegal("mark a no-show before check-in closes");
    }
    BookingStatus from = move(EnumSet.of(CONFIRMED), now);
    status = NO_SHOW;
    return from;
  }

  /** Reaching the end time completes a checked-in booking. */
  public BookingStatus completeAtEnd(Instant now) {
    if (status == CHECKED_IN && now.isBefore(endTime)) {
      throw illegal("complete a booking before it ends");
    }
    return checkOut(now);
  }

  // ---------------------------------------------------------------- people

  public BookingStatus approve(Instant now) {
    requireBeforeHostDeadline(now);
    BookingStatus from = move(EnumSet.of(AWAITING_HOST_APPROVAL), now);
    hostDecisionDueBy = null;
    status = CONFIRMED;
    return from;
  }

  /** Host declines; the authorization is voided (full refund). */
  public BookingStatus decline(Instant now) {
    requireBeforeHostDeadline(now);
    BookingStatus from = move(EnumSet.of(AWAITING_HOST_APPROVAL), now);
    hostDecisionDueBy = null;
    refundAmount = totalAmount;
    status = DECLINED_BY_HOST;
    return from;
  }

  /** Driver cancels: full refund while awaiting approval, otherwise per the snapshotted policy. */
  public BookingStatus cancelByDriver(Instant now) {
    BookingStatus from = move(EnumSet.of(AWAITING_HOST_APPROVAL, CONFIRMED), now);
    refundAmount =
        from == AWAITING_HOST_APPROVAL
            ? totalAmount
            : terms
                .cancellationPolicy()
                .driverRefund(totalAmount, Duration.between(now, startTime));
    hostDecisionDueBy = null;
    status = CANCELLED_BY_DRIVER;
    return from;
  }

  /** Host cancels a confirmed booking: always a full refund. */
  public BookingStatus cancelByHost(Instant now) {
    BookingStatus from = move(EnumSet.of(CONFIRMED), now);
    refundAmount = totalAmount;
    status = CANCELLED_BY_HOST;
    return from;
  }

  public BookingStatus checkIn(Instant now) {
    if (status == CONFIRMED
        && (now.isBefore(startTime.minus(CHECK_IN_OPENS))
            || !now.isBefore(startTime.plus(CHECK_IN_CLOSES)))) {
      throw new DomainException(
          "check-in-window",
          "Check-in is open from 15 minutes before until 30 minutes after the start");
    }
    BookingStatus from = move(EnumSet.of(CONFIRMED), now);
    status = CHECKED_IN;
    return from;
  }

  public BookingStatus checkOut(Instant now) {
    BookingStatus from = move(EnumSet.of(CHECKED_IN), now);
    status = COMPLETED;
    return from;
  }

  // ---------------------------------------------------------------- helpers

  /** Cancel/decline reasons: shown to the other party and sent in events. */
  public static String cleanReason(String reason) {
    return Text.clean(reason, "reason", 1, 255, true, false);
  }

  /** The expiry timer runs every 30 s; a decision after the deadline must not win. */
  private void requireBeforeHostDeadline(Instant now) {
    if (status == AWAITING_HOST_APPROVAL && !now.isBefore(hostDecisionDueBy)) {
      throw illegal("decide after the host's deadline");
    }
  }

  /** True when a Payment event really is about this booking's money. */
  public boolean matchesPayment(UUID driver, UUID host, BigDecimal amount, String currency) {
    return driverAccountId.equals(driver)
        && terms.hostAccountId().equals(host)
        && (amount == null || totalAmount.compareTo(amount) == 0)
        && (currency == null || terms.currency().equals(currency));
  }

  private BookingStatus move(Set<BookingStatus> allowed, Instant now) {
    if (!allowed.contains(status)) {
      throw illegal("change a booking that is " + status);
    }
    if (now.isAfter(updatedAt)) {
      updatedAt = now;
    }
    return status;
  }

  private static DomainException illegal(String what) {
    return new DomainException("illegal-booking-transition", "Cannot " + what);
  }

  public boolean isDriver(UUID accountId) {
    return driverAccountId.equals(accountId);
  }

  public boolean isHost(UUID accountId) {
    return terms.hostAccountId().equals(accountId);
  }

  public Instant blockedUntil() {
    return endTime.plus(Duration.ofMinutes(terms.bufferMinutes()));
  }

  public UUID id() {
    return id;
  }

  public UUID driverAccountId() {
    return driverAccountId;
  }

  public UUID hostAccountId() {
    return terms.hostAccountId();
  }

  public UUID listingId() {
    return listingId;
  }

  public UUID vehicleId() {
    return vehicleId;
  }

  public Instant startTime() {
    return startTime;
  }

  public Instant endTime() {
    return endTime;
  }

  public Terms terms() {
    return terms;
  }

  public BigDecimal totalAmount() {
    return totalAmount;
  }

  public BookingStatus status() {
    return status;
  }

  public Instant paymentDueBy() {
    return paymentDueBy;
  }

  public Instant hostDecisionDueBy() {
    return hostDecisionDueBy;
  }

  public BigDecimal refundAmount() {
    return refundAmount;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public String requestHash() {
    return requestHash;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public int version() {
    return version;
  }
}
