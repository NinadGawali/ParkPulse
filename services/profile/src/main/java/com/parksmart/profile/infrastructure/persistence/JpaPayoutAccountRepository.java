package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.application.port.PayoutAccountRepository;
import com.parksmart.profile.domain.DomainException;
import com.parksmart.profile.domain.PayoutAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayoutAccountRepository implements PayoutAccountRepository {

  private final PayoutAccountJpaRepository jpa;

  JpaPayoutAccountRepository(PayoutAccountJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<PayoutAccount> findById(UUID id) {
    return jpa.findById(id).map(JpaPayoutAccountRepository::toDomain);
  }

  @Override
  public List<PayoutAccount> listByHost(UUID hostAccountId) {
    return jpa.findByHostAccountIdOrderByCreatedAtDescIdDesc(hostAccountId).stream()
        .map(JpaPayoutAccountRepository::toDomain)
        .toList();
  }

  @Override
  public void add(PayoutAccount account) {
    PayoutAccountEntity entity = new PayoutAccountEntity();
    entity.isNew = true;
    entity.id = account.id();
    entity.hostAccountId = account.hostAccountId();
    entity.processorType = account.processorType();
    entity.processorToken = account.processorToken();
    entity.verified = account.verified();
    entity.createdAt = account.createdAt();
    try {
      jpa.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      if (ConstraintViolations.violated(e, "uq_payout_processor_token")) {
        throw new DomainException("payout-registered", "This payout account is already registered");
      }
      throw e;
    }
  }

  @Override
  public void delete(UUID id) {
    jpa.deleteById(id);
  }

  private static PayoutAccount toDomain(PayoutAccountEntity e) {
    return PayoutAccount.restore(
        e.id, e.hostAccountId, e.processorType, e.processorToken, e.verified, e.createdAt);
  }
}
