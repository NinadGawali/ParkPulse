package com.parksmart.listing.infrastructure.persistence;

import com.parksmart.listing.domain.Enums.ListingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ListingJpaRepository extends JpaRepository<ListingEntity, UUID> {

  @Query(
      """
      SELECT l FROM ListingEntity l
      WHERE l.hostAccountId = :host AND l.deletedAt IS NULL
        AND (:status IS NULL OR l.status = :status)
      ORDER BY l.createdAt DESC, l.id DESC
      """)
  List<ListingEntity> firstPageByHost(
      @Param("host") UUID host, @Param("status") ListingStatus status, Limit limit);

  @Query(
      """
      SELECT l FROM ListingEntity l
      WHERE l.hostAccountId = :host AND l.deletedAt IS NULL
        AND (:status IS NULL OR l.status = :status)
        AND (l.createdAt < :after OR (l.createdAt = :after AND l.id < :afterId))
      ORDER BY l.createdAt DESC, l.id DESC
      """)
  List<ListingEntity> nextPageByHost(
      @Param("host") UUID host,
      @Param("status") ListingStatus status,
      @Param("after") Instant after,
      @Param("afterId") UUID afterId,
      Limit limit);

  @Query(
      """
      SELECT l FROM ListingEntity l WHERE l.status = :status
      ORDER BY l.updatedAt ASC, l.id ASC
      """)
  List<ListingEntity> firstPageByStatus(@Param("status") ListingStatus status, Limit limit);

  @Query(
      """
      SELECT l FROM ListingEntity l WHERE l.status = :status
        AND (l.updatedAt > :after OR (l.updatedAt = :after AND l.id > :afterId))
      ORDER BY l.updatedAt ASC, l.id ASC
      """)
  List<ListingEntity> nextPageByStatus(
      @Param("status") ListingStatus status,
      @Param("after") Instant after,
      @Param("afterId") UUID afterId,
      Limit limit);

  List<ListingEntity> findByHostAccountIdAndStatus(UUID host, ListingStatus status);
}
