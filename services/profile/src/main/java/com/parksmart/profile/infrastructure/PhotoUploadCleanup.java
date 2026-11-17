package com.parksmart.profile.infrastructure;

import com.parksmart.profile.application.ProfilePhotoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired photo upload slots and their staging objects. */
@Component
@ConditionalOnProperty(value = "parksmart.profile.upload-cleanup.enabled", matchIfMissing = true)
class PhotoUploadCleanup {

  private static final Logger log = LoggerFactory.getLogger(PhotoUploadCleanup.class);
  private static final int BATCH = 200;

  private final ProfilePhotoService photos;

  PhotoUploadCleanup(ProfilePhotoService photos) {
    this.photos = photos;
  }

  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
  void purge() {
    int removed = photos.purgeExpiredUploads(BATCH);
    if (removed > 0) {
      log.info("Removed {} expired photo upload slots", removed);
    }
  }
}
