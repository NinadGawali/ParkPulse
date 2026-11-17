package com.parksmart.notification.application.port;

import java.util.UUID;

/** De-duplicates consumed events. */
public interface ProcessedEvents {

  /**
   * Records the event as handled by {@code consumer} in the current transaction.
   *
   * @return false if it was already handled (the caller must skip it)
   */
  boolean markProcessed(UUID eventId, String consumer);
}
