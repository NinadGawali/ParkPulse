package com.parksmart.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.identity.ContactDetailsChanged;
import com.parksmart.common.events.identity.RoleGranted;
import com.parksmart.common.events.identity.VerificationCodeIssued;
import com.parksmart.common.events.listing.ListingRejected;
import com.parksmart.common.events.listing.ListingSubmittedForReview;
import com.parksmart.common.events.profile.NotificationPreferencesChanged;
import com.parksmart.notification.application.Dispatcher;
import com.parksmart.notification.application.IncomingEvents;
import com.parksmart.notification.application.port.Senders.EmailSender;
import com.parksmart.notification.application.port.Senders.SmsSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Import(NotificationIT.Capture.class)
class NotificationIT extends IntegrationTest {

  record Sent(String channel, String to, String subject, String body) {}

  static final List<Sent> SENT = new CopyOnWriteArrayList<>();
  static final AtomicBoolean FAIL = new AtomicBoolean();

  @TestConfiguration(proxyBeanMethods = false)
  static class Capture {
    @Bean
    @Primary
    EmailSender capturingEmail() {
      return (to, subject, body) -> {
        if (FAIL.get()) {
          throw new IllegalStateException("smtp down");
        }
        SENT.add(new Sent("EMAIL", to, subject, body));
      };
    }

    @Bean
    @Primary
    SmsSender capturingSms() {
      return (to, body) -> SENT.add(new Sent("SMS", to, null, body));
    }
  }

  @Autowired IncomingEvents events;
  @Autowired Dispatcher dispatcher;

  @BeforeEach
  void reset() {
    FAIL.set(false);
  }

  private void contact(UUID account, String email) {
    events.handle(
        UUID.randomUUID(), new ContactDetailsChanged(account, email, null, Instant.now()));
  }

  private List<Sent> sentTo(String to) {
    return SENT.stream().filter(s -> to.equals(s.to())).toList();
  }

  @Test
  void codes_go_to_their_destination_and_are_redacted_after_sending() {
    UUID account = UUID.randomUUID();
    String to = "code-" + account + "@example.com";
    UUID event = UUID.randomUUID();
    VerificationCodeIssued code =
        new VerificationCodeIssued(
            account, "EMAIL_VERIFY", "EMAIL", to, "482913", Instant.now().plusSeconds(900));
    events.handle(event, code);
    events.handle(event, code); // redelivery
    dispatcher.run(50);
    assertThat(sentTo(to)).hasSize(1);
    assertThat(sentTo(to).get(0).body()).contains("482913");
    String stored =
        jdbc.sql("SELECT body FROM notifications WHERE destination = :d")
            .param("d", to)
            .query(String.class)
            .single();
    assertThat(stored).isEqualTo("[redacted]");

    String phone = "+9198" + (10_000_000 + (int) (Math.random() * 89_999_999));
    events.handle(
        UUID.randomUUID(),
        new VerificationCodeIssued(account, "PHONE_VERIFY", "SMS", phone, "111222", Instant.now()));
    dispatcher.run(50);
    assertThat(sentTo(phone)).extracting(Sent::channel).containsExactly("SMS");
  }

  @Test
  void booking_notices_reach_both_parties_in_app_and_by_email_unless_disabled() throws Exception {
    User driver = driver();
    User host = host();
    String driverEmail = "d-" + driver.id() + "@example.com";
    String hostEmail = "h-" + host.id() + "@example.com";
    contact(driver.id(), driverEmail);
    contact(host.id(), hostEmail);
    events.handle(
        UUID.randomUUID(),
        new NotificationPreferencesChanged(host.id(), false, true, true, Instant.now()));
    Instant start = Instant.parse("2030-01-07T10:00:00Z");
    events.handle(
        UUID.randomUUID(),
        new BookingConfirmed(
            UUID.randomUUID(),
            UUID.randomUUID(),
            driver.id(),
            host.id(),
            start,
            start.plusSeconds(3600),
            start.plusSeconds(3600),
            new BigDecimal("60.00"),
            "INR",
            Instant.now()));
    dispatcher.run(50);
    assertThat(sentTo(driverEmail)).hasSize(1);
    assertThat(sentTo(driverEmail).get(0).body()).contains("60.00 INR");
    assertThat(sentTo(hostEmail)).isEmpty(); // host turned email off

    get("/api/v1/notifications", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].type").value("booking.confirmed.host"));
    get("/api/v1/notifications/unread-count", driver).andExpect(jsonPath("$.unread").value(1));
  }

  @Test
  void user_text_is_cleaned_before_it_reaches_a_message() throws Exception {
    User host = host();
    events.handle(
        UUID.randomUUID(),
        new ListingRejected(UUID.randomUUID(), host.id(), "Blurry‮ photo\u0007", Instant.now()));
    var item = body(get("/api/v1/notifications", host)).get("items").get(0);
    assertThat(item.get("body").asText()).contains("Blurry photo").doesNotContain("‮", "\u0007");
    events.handle(
        UUID.randomUUID(),
        new BookingCancelled(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            host.id(),
            "HOST",
            "Gate broken",
            new BigDecimal("50.00"),
            "INR",
            Instant.now(),
            Instant.now(),
            Instant.now()));
    assertThat(body(get("/api/v1/notifications/unread-count", host)).get("unread").asLong())
        .isEqualTo(2);
  }

  @Test
  void admins_hear_about_the_moderation_queue() throws Exception {
    User admin = user("ADMIN");
    events.handle(UUID.randomUUID(), new RoleGranted(admin.id(), "ADMIN", Instant.now()));
    events.handle(
        UUID.randomUUID(),
        new ListingSubmittedForReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
    get("/api/v1/notifications", admin)
        .andExpect(jsonPath("$.items[0].type").value("listing.review_queue"));
  }

  @Test
  void failed_emails_are_retried_with_backoff_then_dead_lettered() {
    UUID account = UUID.randomUUID();
    String to = "retry-" + account + "@example.com";
    contact(account, to);
    FAIL.set(true);
    events.handle(
        UUID.randomUUID(), new ListingRejected(UUID.randomUUID(), account, "x", Instant.now()));
    dispatcher.run(50);
    var row =
        jdbc.sql(
                "SELECT status, attempts, next_attempt_at > now() AS later FROM notifications WHERE destination = :d")
            .param("d", to)
            .query()
            .singleRow();
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("attempts")).isEqualTo(1);
    assertThat(row.get("later")).isEqualTo(true);
    for (int i = 0; i < 5; i++) {
      jdbc.sql(
              "UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE destination = :d AND status = 'PENDING'")
          .param("d", to)
          .update();
      dispatcher.run(50);
    }
    assertThat(
            jdbc.sql("SELECT status FROM notifications WHERE destination = :d")
                .param("d", to)
                .query(String.class)
                .single())
        .isEqualTo("FAILED");
  }

  @Test
  void only_the_owner_marks_a_notice_read() throws Exception {
    User host = host();
    events.handle(
        UUID.randomUUID(), new ListingRejected(UUID.randomUUID(), host.id(), "x", Instant.now()));
    String id = body(get("/api/v1/notifications", host)).get("items").get(0).get("id").asText();
    post("/api/v1/notifications/" + id + "/read", driver()).andExpect(status().isForbidden());
    post("/api/v1/notifications/" + id + "/read", host).andExpect(status().isNoContent());
    post("/api/v1/notifications/" + UUID.randomUUID() + "/read", host)
        .andExpect(status().isNotFound());
    get("/api/v1/notifications/unread-count", host).andExpect(jsonPath("$.unread").value(0));
    get("/api/v1/notifications", null).andExpect(status().isUnauthorized());
  }
}
