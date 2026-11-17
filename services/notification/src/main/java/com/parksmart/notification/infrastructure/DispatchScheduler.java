package com.parksmart.notification.infrastructure;

import com.parksmart.notification.application.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends due emails/SMS every few seconds (SKIP LOCKED batches: safe with several instances). */
@Component
@ConditionalOnProperty(value = "parksmart.notification.dispatch.enabled", matchIfMissing = true)
class DispatchScheduler {

  private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);
  private static final int BATCH = 50;

  private final Dispatcher dispatcher;

  DispatchScheduler(Dispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${parksmart.notification.dispatch.interval:5s}")
  void dispatch() {
    try {
      int sent;
      do {
        sent = dispatcher.run(BATCH);
      } while (sent == BATCH);
    } catch (RuntimeException e) {
      log.warn("Dispatch run failed; retrying next tick", e);
    }
  }
}
