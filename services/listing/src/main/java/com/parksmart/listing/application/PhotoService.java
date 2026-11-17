package com.parksmart.listing.application;

import com.parksmart.listing.application.port.ListingRepository;
import com.parksmart.listing.application.port.PhotoStorage;
import com.parksmart.listing.application.port.PhotoStorage.PresignedUpload;
import com.parksmart.listing.application.port.PhotoStorage.StoredObject;
import com.parksmart.listing.application.port.Projections.PhotoUploads;
import com.parksmart.listing.application.port.Projections.PhotoUploads.StaleUpload;
import com.parksmart.listing.domain.DomainException;
import com.parksmart.listing.domain.Listing;
import com.parksmart.listing.domain.ListingPhoto;
import com.parksmart.listing.domain.PhotoContentType;
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

/**
 * Listing photos: presigned upload to a private staging key, confirm (server-side copy to the
 * public key, then verify the copy), delete, reorder, and cleanup of stale uploads.
 */
@Service
public class PhotoService {

  private static final Logger log = LoggerFactory.getLogger(PhotoService.class);
  private static final Duration CLEANUP_GRACE = Duration.ofHours(1);

  private final ListingService listingService;
  private final ListingRepository listings;
  private final PhotoUploads uploads;
  private final PhotoStorage storage;
  private final ListingEvents events;
  private final ListingProperties properties;
  private final Clock clock;

  public PhotoService(
      ListingService listingService,
      ListingRepository listings,
      PhotoUploads uploads,
      PhotoStorage storage,
      ListingEvents events,
      ListingProperties properties,
      Clock clock) {
    this.listingService = listingService;
    this.listings = listings;
    this.uploads = uploads;
    this.storage = storage;
    this.events = events;
    this.properties = properties;
    this.clock = clock;
  }

  public record UploadSlot(UUID photoId, PresignedUpload presigned) {}

  @Transactional
  public UploadSlot startUpload(
      UUID callerId, UUID listingId, String contentType, long contentLength) {
    PhotoContentType type =
        PhotoContentType.fromMediaType(contentType)
            .orElseThrow(() ->
                new DomainException(
                    "invalid-contentType", "contentType must be jpeg, png or webp"));
    Listing listing = listingService.owned(callerId, listingId);
    ListingPhoto photo =
        listing.startPhotoUpload(
            UUID.randomUUID(),
            type,
            contentLength,
            properties.maxPhotoBytes(),
            clock.instant(),
            properties.uploadUrlTtl());
    listings.save(listing);
    PresignedUpload presigned =
        storage.presignPut(
            photo.stagingKey(), type.mediaType(), contentLength, properties.uploadUrlTtl());
    return new UploadSlot(photo.id(), presigned);
  }

  @Transactional
  public ListingPhoto confirm(UUID callerId, UUID listingId, UUID photoId) {
    Listing listing = listingService.owned(callerId, listingId);
    Instant now = clock.instant();
    ListingPhoto pending = listing.pendingPhoto(photoId, now);
    if (storage.stat(pending.stagingKey()).isEmpty()) {
      throw new DomainException("upload-missing", "No file was uploaded for this photo");
    }
    // Reject obvious mismatches before anything is public, then copy and verify the copy: the
    // presigned URL can only ever write the staging key.
    if (!matchesDeclared(pending, pending.stagingKey())) {
      throw new DomainException(
          "upload-mismatch", "Uploaded file does not match the declared image type and size");
    }
    String publicKey = listing.publicPhotoKey(photoId);
    storage.copy(pending.stagingKey(), publicKey);
    if (!matchesDeclared(pending, publicKey)) {
      storage.delete(publicKey);
      throw new DomainException(
          "upload-mismatch", "Uploaded file does not match the declared image type and size");
    }
    ListingPhoto ready =
        listing.confirmPhoto(photoId, publicKey, storage.publicUrl(publicKey), now);
    listings.save(listing);
    deleteAfterCommit(pending.stagingKey());
    events.updatedIfLive(listing);
    return ready;
  }

  @Transactional
  public void delete(UUID callerId, UUID listingId, UUID photoId) {
    Listing listing = listingService.owned(callerId, listingId);
    ListingPhoto removed = listing.removePhoto(photoId, clock.instant());
    listings.save(listing);
    Optional.ofNullable(removed.objectKey()).ifPresent(this::deleteAfterCommit);
    Optional.ofNullable(removed.stagingKey()).ifPresent(this::deleteAfterCommit);
    if (removed.isReady()) {
      events.updatedIfLive(listing);
    }
  }

  @Transactional
  public ListingService.Versioned reorder(UUID callerId, UUID listingId, List<UUID> order) {
    Listing listing = listingService.owned(callerId, listingId);
    listing.reorderPhotos(order, clock.instant());
    long version = listings.save(listing);
    events.updatedIfLive(listing);
    return new ListingService.Versioned(listing, version);
  }

  /**
   * Removes unconfirmed photos whose URL expired, and staging objects of confirmed photos (a client
   * may have re-uploaded to the staging key before its URL expired). Returns how many.
   */
  @Transactional
  public int cleanup(int limit) {
    Instant cutoff = clock.instant().minus(CLEANUP_GRACE);
    List<StaleUpload> pending = uploads.expiredPending(cutoff, limit);
    for (StaleUpload stale : pending) {
      uploads.deletePending(stale.photoId());
      deleteAfterCommit(stale.stagingKey());
    }
    List<StaleUpload> staging = uploads.staleStaging(cutoff, limit);
    for (StaleUpload stale : staging) {
      uploads.clearStaging(stale.photoId());
      deleteAfterCommit(stale.stagingKey());
    }
    return pending.size() + staging.size();
  }

  private boolean matchesDeclared(ListingPhoto photo, String key) {
    Optional<StoredObject> stored = storage.stat(key);
    return stored.isPresent()
        && stored.get().contentLength() == photo.contentLength()
        && photo.contentType().mediaType().equalsIgnoreCase(stored.get().contentType())
        && photo
            .contentType()
            .matchesSignature(storage.readHead(key, PhotoContentType.SIGNATURE_BYTES));
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
