package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.DriverRating;
import com.parksmart.profile.domain.Profile;
import com.parksmart.profile.domain.ProfilePhoto;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * Maps {@link Profile} to {@link ProfileEntity}; Hibernate's @Version guards concurrent updates.
 */
@Repository
class JpaProfileRepository implements ProfileRepository {

  private final ProfileJpaRepository jpa;

  JpaProfileRepository(ProfileJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Profile> findById(UUID accountId) {
    return jpa.findById(accountId).map(JpaProfileRepository::toDomain);
  }

  @Override
  public void save(Profile profile) {
    ProfileEntity entity = jpa.findById(profile.accountId()).orElse(null);
    if (entity == null) {
      entity = new ProfileEntity(profile.accountId());
      entity.createdAt = profile.createdAt();
    } else if (entity.version != profile.version()) {
      throw new ObjectOptimisticLockingFailureException(ProfileEntity.class, profile.accountId());
    }
    entity.displayName = profile.displayName();
    entity.bio = profile.bio();
    entity.city = profile.city();
    entity.photoObjectKey = profile.photo().map(ProfilePhoto::objectKey).orElse(null);
    entity.photoUrl = profile.photo().map(ProfilePhoto::url).orElse(null);
    entity.ratingAvg = profile.rating().average();
    entity.ratingCount = profile.rating().count();
    entity.ratingUpdatedAt = profile.rating().asOf();
    entity.updatedAt = profile.updatedAt();
    jpa.save(entity);
  }

  @Override
  public void createIfAbsent(UUID accountId, Instant now) {
    jpa.insertIfAbsent(accountId, now);
  }

  @Override
  public void lockAccount(UUID accountId, Instant now) {
    jpa.insertIfAbsent(accountId, now);
    jpa.lockById(accountId).orElseThrow();
  }

  private static Profile toDomain(ProfileEntity e) {
    ProfilePhoto photo =
        e.photoObjectKey == null ? null : new ProfilePhoto(e.photoObjectKey, e.photoUrl);
    DriverRating rating = new DriverRating(e.ratingAvg, e.ratingCount, e.ratingUpdatedAt);
    return Profile.restore(
        e.accountId,
        e.displayName,
        e.bio,
        e.city,
        photo,
        rating,
        e.createdAt,
        e.updatedAt,
        e.version);
  }
}
