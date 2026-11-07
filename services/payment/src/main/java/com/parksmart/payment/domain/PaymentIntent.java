package com.parksmart.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment per booking. CREATED -> AUTHORIZED | FAILED | VOIDED; AUTHORIZED -> CAPTURED | FAILED
 * (capture rejected) | VOIDED; CAPTURED -> PARTIALLY_REFUNDED -> REFUNDED.
 */
public final class PaymentIntent {

  public enum Status {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    VOIDED,
    REFUNDED,
    PARTIALLY_REFUNDED
  }

  private final UUID id;
  private final UUID bookingId;
  private final UUID driverAccountId;
  private final UUID hostAccountId;
  private final BigDecimal amount;
  private final String currency;
  private Status status;
  private String processorRef;
  private final Instant paymentDueBy;
  private BigDecimal refundedAmount;
  private BigDecimal commissionAmount;
  private String failureCode;
  private final Instant createdAt;
  private Instant updatedAt;
  private int version;

  private PaymentIntent(
      UUID id,
      UUID bookingId,
      UUID driverAccountId,
      UUID hostAccountId,
      BigDecimal amount,
      String currency,
      Status status,
      String processorRef,
      Instant paymentDueBy,
      BigDecimal refundedAmount,
      BigDecimal commissionAmount,
      String failureCode,
      Instant createdAt,
      Instant updatedAt,
      int version) {
    this.id = Objects.requireNonNull(id, "id");
    this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
    this.driverAccountId = Objects.requireNonNull(driverAccountId, "driverAccountId");
    this.hostAccountId = Objects.requireNonNull(hostAccountId, "hostAccountId");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.currency = Objects.requireNonNull(currency, "currency");
    this.status = Objects.requireNonNull(status, "status");
    this.processorRef = processorRef;
    this.paymentDueBy = Objects.requireNonNull(paymentDueBy, "paymentDueBy");
    this.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount");
    this.commissionAmount = commissionAmount;
    this.failureCode = failureCode;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.version = version;
  }

  /** From BookingRequested. */
  public static PaymentIntent create(
      UUID id,
      UUID bookingId,
      UUID driverAccountId,
      UUID hostAccountId,
      BigDecimal amount,
      String currency,
      Instant paymentDueBy,
      Instant now) {
    if (amount.signum() <= 0 || amount.scale() > 2) {
      throw new DomainException(
          "invalid-amount", "amount must be positive with at most 2 decimals");
    }
    return new PaymentIntent(
        id,
        bookingId,
        driverAccountId,
        hostAccountId,
        amount.setScale(2),
        currency,
        Status.CREATED,
        null,
        paymentDueBy,
        BigDecimal.ZERO.setScale(2),
        null,
        null,
        now,
        now,
        0);
  }

  public static PaymentIntent restore(
      UUID id,
      UUID bookingId,
      UUID driverAccountId,
      UUID hostAccountId,
      BigDecimal amount,
      String currency,
      Status status,
      String processorRef,
      Instant paymentDueBy,
      BigDecimal refundedAmount,
      BigDecimal commissionAmount,
      String failureCode,
      Instant createdAt,
      Instant updatedAt,
      int version) {
    return new PaymentIntent(
        id,
        bookingId,
        driverAccountId,
        hostAccountId,
        amount,
        currency,
        status,
        processorRef,
        paymentDueBy,
        refundedAmount,
        commissionAmount,
        failureCode,
        createdAt,
        updatedAt,
        version);
  }

  /** Whether the processor may be asked to authorize now (CREATED, inside the payment window). */
  public void checkAuthorizable(Instant now) {
    if (status == Status.VOIDED || (status == Status.CREATED && !now.isBefore(paymentDueBy))) {
      throw new DomainException(
          "payment-window-closed", "The payment window for this booking has closed");
    }
  }

  public void authorized(String ref, Instant now) {
    require(Status.CREATED);
    processorRef = ref;
    status = Status.AUTHORIZED;
    touch(now);
  }

  public void authorizationFailed(String code, Instant now) {
    require(Status.CREATED);
    failureCode = code;
    status = Status.FAILED;
    touch(now);
  }

  public void captured(BigDecimal commission, Instant now) {
    require(Status.AUTHORIZED);
    commissionAmount = commission;
    status = Status.CAPTURED;
    touch(now);
  }

  public void captureFailed(String code, Instant now) {
    require(Status.AUTHORIZED);
    failureCode = code;
    status = Status.FAILED;
    touch(now);
  }

  /** CREATED or AUTHORIZED: the hold (if any) is released. */
  public void voided(Instant now) {
    require(Status.CREATED, Status.AUTHORIZED);
    status = Status.VOIDED;
    touch(now);
  }

  /** Validates a refund before the processor is called (state, amount, what is left). */
  public void checkRefundable(BigDecimal refund) {
    require(Status.CAPTURED, Status.PARTIALLY_REFUNDED);
    if (refund.signum() <= 0 || refund.scale() > 2) {
      throw new DomainException(
          "invalid-amount", "refund must be positive with at most 2 decimals");
    }
    if (refund.compareTo(remaining()) > 0) {
      throw new DomainException(
          "refund-exceeds-captured", "Refund exceeds the remaining captured amount");
    }
  }

  /** Refunds part or all of what is left. Returns the refund applied. */
  public BigDecimal refunded(BigDecimal refund, Instant now) {
    require(Status.CAPTURED, Status.PARTIALLY_REFUNDED);
    if (refund.signum() <= 0 || refund.scale() > 2) {
      throw new DomainException(
          "invalid-amount", "refund must be positive with at most 2 decimals");
    }
    if (refund.compareTo(remaining()) > 0) {
      throw new DomainException(
          "refund-exceeds-captured", "Refund exceeds the remaining captured amount");
    }
    refundedAmount = refundedAmount.add(refund).setScale(2);
    status = refundedAmount.compareTo(amount) == 0 ? Status.REFUNDED : Status.PARTIALLY_REFUNDED;
    touch(now);
    return refund.setScale(2);
  }

  public BigDecimal remaining() {
    return amount.subtract(refundedAmount);
  }

  private void require(Status... allowed) {
    for (Status s : allowed) {
      if (status == s) {
        return;
      }
    }
    throw new DomainException(
        "illegal-payment-transition", "The payment is " + status + " and cannot change this way");
  }

  private void touch(Instant now) {
    if (now.isAfter(updatedAt)) {
      updatedAt = now;
    }
  }

  public boolean isDriver(UUID accountId) {
    return driverAccountId.equals(accountId);
  }

  public UUID id() {
    return id;
  }

  public UUID bookingId() {
    return bookingId;
  }

  public UUID driverAccountId() {
    return driverAccountId;
  }

  public UUID hostAccountId() {
    return hostAccountId;
  }

  public BigDecimal amount() {
    return amount;
  }

  public String currency() {
    return currency;
  }

  public Status status() {
    return status;
  }

  public String processorRef() {
    return processorRef;
  }

  public Instant paymentDueBy() {
    return paymentDueBy;
  }

  public BigDecimal refundedAmount() {
    return refundedAmount;
  }

  public BigDecimal commissionAmount() {
    return commissionAmount;
  }

  public String failureCode() {
    return failureCode;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /** Called by the repository after a successful write (several writes may share a transaction). */
  public void stored() {
    version++;
  }

  public int version() {
    return version;
  }
}
