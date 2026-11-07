package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.application.port.PhotoUploadRepository;
import com.parksmart.profile.domain.PhotoContentType;
import com.parksmart.profile.domain.PhotoUpload;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
class JpaPhotoUploadRepository implements PhotoUploadRepository {

  private final PhotoUploadJpaRepository jpa;
  private final Clock clock;

  JpaPhotoUploadRepository(PhotoUploadJpaRepository jpa, Clock clock) {
    this.jpa = jpa;
    this.clock = clock;
  }

  @Override
  public Optional<PhotoUpload> findForUpdate(UUID id) {
    return jpa.lockById(id).map(JpaPhotoUploadRepository::toDomain);
  }

  @Override
  public void save(PhotoUpload upload) {
    PhotoUploadEntity entity = jpa.findById(upload.id()).orElse(null);
    if (entity == null) {
      entity = new PhotoUploadEntity();
      entity.isNew = true;
      entity.id = upload.id();
      entity.accountId = upload.accountId();
      entity.objectKey = upload.objectKey();
      entity.contentType = upload.contentType().mediaType();
      entity.contentLength = upload.contentLength();
      entity.expiresAt = upload.expiresAt();
      entity.createdAt = clock.instant();
    }
    entity.confirmedAt = upload.confirmedAt();
    jpa.save(entity);
  }

  @Override
  public long countPending(UUID accountId, Instant now) {
    return jpa.countByAccountIdAndConfirmedAtIsNullAndExpiresAtAfter(accountId, now);
  }

  @Override
  public List<PhotoUpload> findExpiredUnconfirmed(Instant cutoff, int limit) {
    return jpa
        .findByConfirmedAtIsNullAndExpiresAtBeforeOrderByExpiresAtAsc(cutoff, Limit.of(limit))
        .stream()
        .map(JpaPhotoUploadRepository::toDomain)
        .toList();
  }

  @Override
  public void delete(UUID id) {
    jpa.deleteById(id);
  }

  private static PhotoUpload toDomain(PhotoUploadEntity e) {
    return PhotoUpload.restore(
        e.id,
        e.accountId,
        e.objectKey,
        PhotoContentType.fromMediaType(e.contentType).orElseThrow(),
        e.contentLength,
        e.expiresAt,
        e.confirmedAt);
  }
}
