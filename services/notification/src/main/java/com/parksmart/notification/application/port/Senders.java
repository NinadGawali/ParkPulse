package com.parksmart.notification.application.port;

/** Delivery channels. Implementations throw on failure; the dispatcher retries with backoff. */
public final class Senders {

  private Senders() {}

  /** Email (SES-compatible adapter on aws; a logging stand-in locally). */
  public interface EmailSender {
    void send(String to, String subject, String body);
  }

  /** SMS (provider adapter on aws; a logging stand-in locally). */
  public interface SmsSender {
    void send(String to, String body);
  }
}
