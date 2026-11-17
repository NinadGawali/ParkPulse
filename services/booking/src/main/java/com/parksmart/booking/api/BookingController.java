package com.parksmart.booking.api;

import com.parksmart.booking.api.BookingDtos.BookingResponse;
import com.parksmart.booking.api.BookingDtos.CreateBooking;
import com.parksmart.booking.api.BookingDtos.Reason;
import com.parksmart.booking.application.BookingService;
import com.parksmart.booking.application.BookingService.CreateCommand;
import com.parksmart.booking.application.BookingService.Created;
import com.parksmart.booking.application.port.BookingRepository.Role;
import com.parksmart.booking.application.port.BookingRepository.When;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.CursorPage;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Party checks (driver / host, 403 otherwise) happen in BookingService. */
@RestController
@RequestMapping("/api/v1/bookings")
class BookingController {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

  private final BookingService bookings;

  BookingController(BookingService bookings) {
    this.bookings = bookings;
  }

  /** 202 for a new booking (payment pending), 200 for a replay of the same request. */
  @PostMapping
  ResponseEntity<BookingResponse> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateBooking body) {
    if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw ApiException.badRequest(
          "validation-failed",
          "Validation failed",
          "Idempotency-Key must be 8-100 letters, digits, '_' or '-'");
    }
    AuthenticatedAccount driver =
        CurrentAccount.requireRole(com.parksmart.common.security.Role.DRIVER);
    Created created =
        bookings.create(
            driver,
            idempotencyKey,
            new CreateCommand(
                body.listingId(), body.vehicleId(), body.startTime(), body.endTime()));
    return ResponseEntity.status(created.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(BookingResponse.of(created.booking()));
  }

  @GetMapping
  CursorPage<BookingResponse> list(
      @RequestParam String role,
      @RequestParam(defaultValue = "all") String when,
      @RequestParam(required = false) BookingStatus status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return bookings
        .page(
            CurrentAccount.require().accountId(),
            parse(Role.class, role, "role"),
            parse(When.class, when, "when"),
            status,
            cursor,
            limit)
        .map(BookingResponse::of);
  }

  @GetMapping("/{bookingId}")
  BookingResponse get(@PathVariable UUID bookingId) {
    return BookingResponse.of(bookings.get(CurrentAccount.require(), bookingId));
  }

  @PostMapping("/{bookingId}/cancel")
  BookingResponse cancel(@PathVariable UUID bookingId, @Valid @RequestBody Reason body) {
    return BookingResponse.of(bookings.cancel(callerId(), bookingId, body.reason()));
  }

  @PostMapping("/{bookingId}/approve")
  BookingResponse approve(@PathVariable UUID bookingId) {
    return BookingResponse.of(bookings.approve(callerId(), bookingId));
  }

  @PostMapping("/{bookingId}/decline")
  BookingResponse decline(@PathVariable UUID bookingId, @Valid @RequestBody Reason body) {
    return BookingResponse.of(bookings.decline(callerId(), bookingId, body.reason()));
  }

  @PostMapping("/{bookingId}/check-in")
  BookingResponse checkIn(@PathVariable UUID bookingId) {
    return BookingResponse.of(bookings.checkIn(callerId(), bookingId));
  }

  @PostMapping("/{bookingId}/check-out")
  BookingResponse checkOut(@PathVariable UUID bookingId) {
    return BookingResponse.of(bookings.checkOut(callerId(), bookingId));
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("validation-failed", "Validation failed", "Invalid " + name);
    }
  }
}
