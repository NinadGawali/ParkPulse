package com.parksmart.profile.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PhotoUploadJpaRepository extends JpaRepository<PhotoUploadEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM PhotoUploadEntity u WHERE u.id = :id")
  Optional<PhotoUploadEntity> lockById(@Param("id") UUID id);

  long countByAccountIdAndConfirmedAtIsNullAndExpiresAtAfter(UUID accountId, Instant now);

  List<PhotoUploadEntity> findByConfirmedAtIsNullAndExpiresAtBeforeOrderByExpiresAtAsc(
      Instant cutoff, Limit limit);
}
