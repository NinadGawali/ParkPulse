package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.infrastructure.persistence.NotificationPreferenceEntity.Key;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationPreferenceJpaRepository
    extends JpaRepository<NotificationPreferenceEntity, Key> {
  List<NotificationPreferenceEntity> findByKeyAccountId(UUID accountId);
}
