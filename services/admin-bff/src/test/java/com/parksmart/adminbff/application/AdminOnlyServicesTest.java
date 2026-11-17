package com.parksmart.adminbff.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parksmart.adminbff.application.port.AdminCall;
import com.parksmart.adminbff.application.port.Downstream;
import com.parksmart.adminbff.domain.Facts;
import com.parksmart.common.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The ADMIN check lives in the application layer too (hard rule 7), so a DRIVER/HOST is refused
 * even if the filter chain were misconfigured, and nothing is forwarded.
 */
class AdminOnlyServicesTest {

  final Downstream.Listings listings = mock(Downstream.Listings.class);
  final Downstream.Profiles profiles = mock(Downstream.Profiles.class);
  final Downstream.Reviews reviews = mock(Downstream.Reviews.class);
  final Downstream.Bookings bookings = mock(Downstream.Bookings.class);
  final Downstream.Payments payments = mock(Downstream.Payments.class);
  final ParallelLookups lookups = new ParallelLookups((Executor) Runnable::run);
  final ModerationService moderation = new ModerationService(listings, profiles, reviews, lookups);
  final BookingCaseService cases =
      new BookingCaseService(bookings, payments, listings, profiles, lookups);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  static void signIn(String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("token-" + String.join("-", roles))
            .header("alg", "RS256")
            .subject(UUID.randomUUID().toString())
            .claim("roles", List.of(roles))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  static List<Consumer<Void>> everyUseCase(ModerationService m, BookingCaseService c) {
    UUID id = UUID.randomUUID();
    return List.of(
        v -> m.queue(null, 20),
        v -> m.approve(id, null),
        v -> m.reject(id, "reason"),
        v -> m.hideReview(id, "reason"),
        v -> c.find(id),
        v -> c.refund(id, "refund-key-1", "1.00", "reason"));
  }

  @Test
  void driver_and_host_get_403_from_every_use_case() {
    for (String[] roles : List.of(new String[] {"DRIVER"}, new String[] {"DRIVER", "HOST"})) {
      signIn(roles);
      for (Consumer<Void> useCase : everyUseCase(moderation, cases)) {
        assertThatThrownBy(() -> useCase.accept(null))
            .isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
      }
    }
    verifyNoInteractions(listings, profiles, reviews, bookings, payments);
  }

  @Test
  void anonymous_gets_401() {
    assertThatThrownBy(() -> moderation.queue(null, 20))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(listings);
  }

  @Test
  void admin_token_is_what_gets_forwarded() {
    signIn("ADMIN");
    UUID id = UUID.randomUUID();
    Facts.Listing published =
        new Facts.Listing(id, UUID.randomUUID(), "t", "PUBLISHED", "a", 1, 1, null, Instant.now());
    when(listings.approve(any(), eq(id), isNull())).thenReturn(published);

    assertThat(moderation.approve(id, null)).isEqualTo(published);
    verify(listings)
        .approve(argThat((AdminCall c) -> c.accessToken().equals("token-ADMIN")), eq(id), isNull());
  }

  @Test
  void refund_of_a_booking_without_intent_is_404_and_never_calls_refund() {
    signIn("ADMIN");
    UUID booking = UUID.randomUUID();
    when(payments.findForBooking(any(), eq(booking))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> cases.refund(booking, "refund-key-1", "1.00", "r"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.slug()).isEqualTo("payment-not-found"));

    // An intent of another booking is never refunded either.
    when(payments.findForBooking(any(), eq(booking)))
        .thenReturn(
            Optional.of(
                new Facts.Payment(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BigDecimal.TEN,
                    "INR",
                    "CAPTURED",
                    null,
                    BigDecimal.ZERO,
                    Instant.now())));
    assertThatThrownBy(() -> cases.refund(booking, "refund-key-1", "1.00", "r"))
        .isInstanceOf(ApiException.class);
    verify(payments, org.mockito.Mockito.never()).refund(any(), any(), any(), any(), any());
  }
}
