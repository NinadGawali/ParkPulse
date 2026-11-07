package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.domain.ProcessorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "payout_accounts")
class PayoutAccountEntity implements Persistable<UUID> {

  @Id UUID id;

  @Column(name = "host_account_id", nullable = false, updatable = false)
  UUID hostAccountId;

  @Column(name = "processor_token", nullable = false, updatable = false)
  String processorToken;

  @Enumerated(EnumType.STRING)
  @Column(name = "processor_type", nullable = false, updatable = false, length = 30)
  ProcessorType processorType;

  @Column(name = "verified", nullable = false)
  boolean verified;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  /** Assigned IDs: tells Spring Data to persist (not merge) a new row. */
  @jakarta.persistence.Transient boolean isNew;

  protected PayoutAccountEntity() {}

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }
}
