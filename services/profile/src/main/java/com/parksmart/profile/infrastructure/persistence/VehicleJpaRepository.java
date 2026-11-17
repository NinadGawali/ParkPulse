package com.parksmart.profile.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleJpaRepository extends JpaRepository<VehicleEntity, UUID> {

  Optional<VehicleEntity> findByIdAndDeletedAtIsNull(UUID id);

  List<VehicleEntity> findByOwnerAccountIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID owner);
}
