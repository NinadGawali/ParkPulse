package com.parksmart.listing.infrastructure;

import com.parksmart.listing.application.PhotoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired, unconfirmed photo slots and leftover staging objects. */
@Component
@ConditionalOnProperty(value = "parksmart.listing.photo-cleanup.enabled", matchIfMissing = true)
class PhotoCleanup {

  private static final Logger log = LoggerFactory.getLogger(PhotoCleanup.class);
  private static final int BATCH = 200;

  private final PhotoService photos;

  PhotoCleanup(PhotoService photos) {
    this.photos = photos;
  }

  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
  void purge() {
    int removed = photos.cleanup(BATCH);
    if (removed > 0) {
      log.info("Cleaned up {} stale photo uploads", removed);
    }
  }
}
