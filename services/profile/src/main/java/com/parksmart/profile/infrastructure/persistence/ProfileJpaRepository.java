package com.parksmart.profile.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProfileJpaRepository extends JpaRepository<ProfileEntity, UUID> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO profiles (account_id, created_at, updated_at)
          VALUES (:id, :now, :now) ON CONFLICT (account_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(@Param("id") UUID accountId, @Param("now") Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM ProfileEntity p WHERE p.accountId = :id")
  Optional<ProfileEntity> lockById(@Param("id") UUID accountId);
}
