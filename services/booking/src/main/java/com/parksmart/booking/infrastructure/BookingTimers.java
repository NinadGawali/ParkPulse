package com.parksmart.booking.infrastructure;

import com.parksmart.booking.application.BookingSaga;
import com.parksmart.booking.application.port.BookingRepository.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timed transitions: payment window, host decision deadline, no-show, completion at end time.
 * Batches are locked with SKIP LOCKED, so several instances can run.
 */
@Component
@ConditionalOnProperty(value = "parksmart.booking.timers.enabled", matchIfMissing = true)
class BookingTimers {

  private static final Logger log = LoggerFactory.getLogger(BookingTimers.class);
  private static final int BATCH = 100;

  private final BookingSaga saga;

  BookingTimers(BookingSaga saga) {
    this.saga = saga;
  }

  @Scheduled(fixedDelayString = "${parksmart.booking.timers.interval:30s}")
  void run() {
    for (Timer timer : Timer.values()) {
      try {
        int total = 0;
        int moved;
        do { // drain the backlog so expired slots are freed promptly
          moved = saga.runTimer(timer, BATCH);
          total += moved;
        } while (moved == BATCH);
        if (total > 0) {
          log.info("{}: {} bookings moved", timer, total);
        }
      } catch (RuntimeException e) {
        log.warn("Timer {} failed; will retry", timer, e);
      }
    }
  }
}
