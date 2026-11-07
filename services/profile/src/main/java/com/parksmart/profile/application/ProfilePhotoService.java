package com.parksmart.profile.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.profile.application.port.EventOutbox;
import com.parksmart.profile.application.port.PhotoStorage;
import com.parksmart.profile.application.port.PhotoStorage.PresignedUpload;
import com.parksmart.profile.application.port.PhotoStorage.StoredObject;
import com.parksmart.profile.application.port.PhotoUploadRepository;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.DomainException;
import com.parksmart.profile.domain.PhotoContentType;
import com.parksmart.profile.domain.PhotoUpload;
import com.parksmart.profile.domain.Profile;
import com.parksmart.profile.domain.ProfilePhoto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Profile photos: presigned upload, confirm, delete, and cleanup of abandoned uploads. */
@Service
public class ProfilePhotoService {

  private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

  /** Abandoned uploads are removed this long after their URL expired. */
  private static final Duration CLEANUP_GRACE = Duration.ofHours(1);

  private final ProfileRepository profiles;
  private final PhotoUploadRepository uploads;
  private final PhotoStorage storage;
  private final EventOutbox outbox;
  private final ProfileProperties properties;
  private final Clock clock;

  public ProfilePhotoService(
      ProfileRepository profiles,
      PhotoUploadRepository uploads,
      PhotoStorage storage,
      EventOutbox outbox,
      ProfileProperties properties,
      Clock clock) {
    this.profiles = profiles;
    this.uploads = uploads;
    this.storage = storage;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = clock;
  }

  public record UploadSlot(UUID uploadId, PresignedUpload presigned) {}

  @Transactional
  public UploadSlot startUpload(UUID accountId, String contentType, long contentLength) {
    PhotoContentType type =
        PhotoContentType.fromMediaType(contentType)
            .orElseThrow(() ->
                new DomainException(
                    "invalid-contentType", "contentType must be jpeg, png or webp"));
    Instant now = clock.instant();
    profiles.lockAccount(accountId, now);
    if (uploads.countPending(accountId, now) >= PhotoUpload.MAX_PENDING) {
      throw new DomainException(
          "too-many-pending-uploads",
          "Confirm or wait for earlier uploads to expire before requesting another");
    }
    PhotoUpload upload =
        PhotoUpload.start(
            UUID.randomUUID(), accountId, type, contentLength, now, properties.uploadUrlTtl());
    uploads.save(upload);
    PresignedUpload presigned =
        storage.presignPut(
            upload.objectKey(), type.mediaType(), contentLength, properties.uploadUrlTtl());
    return new UploadSlot(upload.id(), presigned);
  }

  /**
   * Verifies the uploaded object (exists, declared type and size, real image signature) and makes
   * it the profile photo. Another account's upload is reported as not found.
   */
  @Transactional
  public Profile confirm(UUID accountId, UUID uploadId) {
    Instant now = clock.instant();
    profiles.lockAccount(accountId, now);
    PhotoUpload upload =
        uploads
            .findForUpdate(uploadId)
            .filter(u -> u.isOwnedBy(accountId))
            .orElseThrow(() -> ApiException.notFound("Upload"));
    upload.confirm(now);

    StoredObject stored =
        storage
            .stat(upload.objectKey())
            .orElseThrow(
                () -> new DomainException("upload-missing", "No file was uploaded for this slot"));
    boolean matches =
        stored.contentLength() == upload.contentLength()
            && upload.contentType().mediaType().equalsIgnoreCase(stored.contentType())
            && upload
                .contentType()
                .matchesSignature(
                    storage.readHead(upload.objectKey(), PhotoContentType.SIGNATURE_BYTES));
    if (!matches) {
      throw new DomainException(
          "upload-mismatch", "Uploaded file does not match the declared image type and size");
    }

    Profile profile = profiles.findById(accountId).orElseThrow();
    Optional<ProfilePhoto> previous =
        profile.replacePhoto(
            new ProfilePhoto(upload.objectKey(), storage.publicUrl(upload.objectKey())), now);
    profiles.save(profile);
    uploads.save(upload);
    outbox.publish(ProfileService.updatedEvent(profile));
    previous.ifPresent(photo -> deleteAfterCommit(photo.objectKey()));
    return profile;
  }

  @Transactional
  public void delete(UUID accountId) {
    Instant now = clock.instant();
    profiles.lockAccount(accountId, now);
    Profile profile = profiles.findById(accountId).orElseThrow();
    Optional<ProfilePhoto> previous = profile.removePhoto(now);
    if (previous.isEmpty()) {
      return;
    }
    profiles.save(profile);
    outbox.publish(ProfileService.updatedEvent(profile));
    deleteAfterCommit(previous.get().objectKey());
  }

  /** Removes uploads that were never confirmed, and their objects. Returns how many. */
  @Transactional
  public int purgeAbandonedUploads(int limit) {
    List<PhotoUpload> expired =
        uploads.findExpiredUnconfirmed(clock.instant().minus(CLEANUP_GRACE), limit);
    for (PhotoUpload upload : expired) {
      uploads.delete(upload.id());
      deleteAfterCommit(upload.objectKey());
    }
    return expired.size();
  }

  /** Storage is not transactional: delete only once the DB no longer references the object. */
  private void deleteAfterCommit(String objectKey) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              storage.delete(objectKey);
            } catch (RuntimeException e) {
              log.warn("Could not delete photo object {}; it is now orphaned", objectKey, e);
            }
          }
        });
  }
}
