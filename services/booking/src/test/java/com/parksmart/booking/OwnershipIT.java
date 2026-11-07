package com.parksmart.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hard rule 7: only the right party may change a booking (403 for everyone else). */
class OwnershipIT extends BookingIT {

  @Test
  void strangers_and_admins_get_403_on_every_mutation() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), false, "FLEXIBLE", 0);
    User driver = driver();
    UUID b = id(booked(driver, listing, slot(24), slot(25)));
    authorize(b); // AWAITING_HOST_APPROVAL
    String base = "/api/v1/bookings/" + b;

    for (User stranger : List.of(driver(), host(), user("ADMIN"))) {
      postJson(base + "/cancel", Map.of("reason", "x"), stranger)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/forbidden"));
      post(base + "/approve", stranger).andExpect(status().isForbidden());
      postJson(base + "/decline", Map.of("reason", "x"), stranger)
          .andExpect(status().isForbidden());
      post(base + "/check-in", stranger).andExpect(status().isForbidden());
      post(base + "/check-out", stranger).andExpect(status().isForbidden());
    }
    // The driver cannot act as the host, and the host cannot check in.
    post(base + "/approve", driver).andExpect(status().isForbidden());
    postJson(base + "/decline", Map.of("reason", "x"), driver).andExpect(status().isForbidden());
    post(base + "/check-in", host).andExpect(status().isForbidden());
    // Host cancel is only for CONFIRMED (a request is declined instead).
    postJson(base + "/cancel", Map.of("reason", "x"), host).andExpect(status().isConflict());
    assertThat(statusOf(b)).isEqualTo("AWAITING_HOST_APPROVAL");
  }

  @Test
  void unknown_bookings_are_404_and_anonymous_is_401() throws Exception {
    post("/api/v1/bookings/" + UUID.randomUUID() + "/approve", host())
        .andExpect(status().isNotFound());
    get("/api/v1/bookings?role=driver", null).andExpect(status().isUnauthorized());
  }
}
