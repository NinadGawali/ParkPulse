package com.parksmart.notification.application;

import com.parksmart.notification.application.port.NotificationStore;
import com.parksmart.notification.application.port.NotificationStore.Channel;
import com.parksmart.notification.application.port.NotificationStore.Notification;
import com.parksmart.notification.application.port.Senders.EmailSender;
import com.parksmart.notification.application.port.Senders.SmsSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends due email/SMS notifications. Failures are retried with exponential backoff (30 s, 1 min, 2
 * min, ...); after the last attempt the row is FAILED (the dead letter, visible to admins).
 * Sensitive bodies (one-time codes) are redacted once sent, failed, or older than a day.
 */
@Service
public class Dispatcher {

  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
  private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  private static final Duration CODE_LIFETIME = Duration.ofHours(24);

  private final NotificationStore store;
  private final EmailSender email;
  private final SmsSender sms;
  private final Clock clock;
  private final int maxAttempts;

  public Dispatcher(
      NotificationStore store,
      EmailSender email,
      SmsSender sms,
      Clock clock,
      @Value("${parksmart.notification.dispatch.max-attempts:5}") int maxAttempts) {
    this.store = store;
    this.email = email;
    this.sms = sms;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  /** Sends one locked batch. Returns how many were sent. */
  @Transactional
  public int run(int limit) {
    Instant now = clock.instant();
    store.expireSensitive(now.minus(CODE_LIFETIME));
    int sent = 0;
    for (Notification n : store.lockDue(now, limit)) {
      try {
        if (n.channel() == Channel.EMAIL) {
          email.send(n.destination(), n.title(), n.body());
        } else {
          sms.send(n.destination(), n.body());
        }
        store.markSent(n.id(), now, n.sensitive());
        sent++;
      } catch (RuntimeException e) {
        int attempts = n.attempts() + 1;
        String error = e.getClass().getSimpleName();
        if (attempts >= maxAttempts) {
          log.warn(
              "Notification {} ({}) failed for good after {} attempts",
              n.id(),
              n.template(),
              attempts);
          store.markFailed(n.id(), attempts, error, n.sensitive());
        } else {
          Instant next = now.plus(FIRST_RETRY.multipliedBy(1L << (attempts - 1)));
          store.markRetry(n.id(), attempts, next, error);
        }
      }
    }
    return sent;
  }
}
