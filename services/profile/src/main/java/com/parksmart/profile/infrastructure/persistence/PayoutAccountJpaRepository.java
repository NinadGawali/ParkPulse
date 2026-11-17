package com.parksmart.profile.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PayoutAccountJpaRepository extends JpaRepository<PayoutAccountEntity, UUID> {
  List<PayoutAccountEntity> findByHostAccountIdOrderByCreatedAtDescIdDesc(UUID hostAccountId);
}
