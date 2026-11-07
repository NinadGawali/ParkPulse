package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class VehicleIT extends IntegrationTest {

  /** Plates are global per region, so every test uses fresh ones. */
  private static String plate() {
    return "MH" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
  }

  private static Map<String, Object> vehicle(String plate) {
    Map<String, Object> body = new HashMap<>();
    body.put("licensePlate", plate);
    body.put("plateRegion", "IN-MH");
    body.put("vehicleType", "SEDAN");
    body.put("make", "Maruti");
    return body;
  }

  private String add(User user, String plate) throws Exception {
    return body(postJson("/api/v1/vehicles", vehicle(plate), user).andExpect(status().isCreated()))
        .get("id")
        .asText();
  }

  @Test
  void first_vehicle_is_primary_and_plates_are_normalized() throws Exception {
    User user = driver();
    String plate = plate();
    String spaced = plate.substring(0, 2) + "-" + plate.substring(2).toLowerCase();
    postJson("/api/v1/vehicles", vehicle(spaced), user)
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/vehicles/")))
        .andExpect(jsonPath("$.licensePlate").value(plate))
        .andExpect(jsonPath("$.primary").value(true));
    String second = add(user, plate());

    get("/api/v1/vehicles", user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].primary").value(true))
        .andExpect(jsonPath("$.items[1].id").value(second))
        .andExpect(jsonPath("$.items[1].primary").value(false));

    assertThat(events("VehicleAdded", "ownerAccountId", user.id())).hasSize(2);
  }

  @Test
  void a_plate_is_unique_per_region_across_accounts() throws Exception {
    String plate = plate();
    add(driver(), plate);
    postJson("/api/v1/vehicles", vehicle(plate), driver())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/plate-registered"));

    Map<String, Object> otherRegion = vehicle(plate);
    otherRegion.put("plateRegion", "IN-KA");
    postJson("/api/v1/vehicles", otherRegion, driver()).andExpect(status().isCreated());
  }

  @Test
  void a_removed_plate_can_be_registered_again() throws Exception {
    User first = driver();
    String plate = plate();
    String id = add(first, plate);
    delete("/api/v1/vehicles/" + id, first).andExpect(status().isNoContent());
    postJson("/api/v1/vehicles", vehicle(plate), driver()).andExpect(status().isCreated());
  }

  @Test
  void non_owner_gets_403_on_every_vehicle_endpoint() throws Exception {
    User owner = driver();
    User stranger = driver();
    String id = add(owner, plate());
    String path = "/api/v1/vehicles/" + id;

    get(path, stranger).andExpect(status().isForbidden());
    putJson(path, Map.of("vehicleType", "SUV"), stranger)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/forbidden"));
    post(path + "/primary", stranger).andExpect(status().isForbidden());
    delete(path, stranger).andExpect(status().isForbidden());

    // Plates are personal data: admins get 403 too (the contract says owner only).
    User admin = user("ADMIN");
    get(path, admin).andExpect(status().isForbidden());
    delete(path, admin).andExpect(status().isForbidden());

    get(path, owner).andExpect(status().isOk()).andExpect(jsonPath("$.vehicleType").value("SEDAN"));
  }

  @Test
  void update_changes_details_but_not_the_plate() throws Exception {
    User user = driver();
    String plate = plate();
    String id = add(user, plate);
    Map<String, Object> body = new HashMap<>();
    body.put("vehicleType", "SUV");
    body.put("color", "Red");
    body.put("licensePlate", "IGNORED1");
    putJson("/api/v1/vehicles/" + id, body, user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vehicleType").value("SUV"))
        .andExpect(jsonPath("$.color").value("Red"))
        .andExpect(jsonPath("$.make").isEmpty())
        .andExpect(jsonPath("$.licensePlate").value(plate));
  }

  @Test
  void make_primary_switches_and_removing_primary_promotes_the_oldest() throws Exception {
    User user = driver();
    String first = add(user, plate());
    String second = add(user, plate());
    String third = add(user, plate());

    post("/api/v1/vehicles/" + third + "/primary", user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primary").value(true));
    post("/api/v1/vehicles/" + third + "/primary", user).andExpect(status().isOk());
    get("/api/v1/vehicles/" + first, user).andExpect(jsonPath("$.primary").value(false));

    delete("/api/v1/vehicles/" + third, user).andExpect(status().isNoContent());
    get("/api/v1/vehicles/" + first, user).andExpect(jsonPath("$.primary").value(true));
    get("/api/v1/vehicles/" + second, user).andExpect(jsonPath("$.primary").value(false));
    get("/api/v1/vehicles/" + third, user).andExpect(status().isNotFound());

    assertThat(events("VehicleRemoved", "vehicleId", UUID.fromString(third))).hasSize(1);
  }

  @Test
  void at_most_ten_vehicles() throws Exception {
    User user = driver();
    for (int i = 0; i < 10; i++) {
      add(user, plate());
    }
    postJson("/api/v1/vehicles", vehicle(plate()), user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/vehicle-limit"));
  }

  @Test
  void validation_errors_are_400() throws Exception {
    Map<String, Object> body = vehicle("MH12$$");
    body.put("plateRegion", "india");
    postJson("/api/v1/vehicles", body, driver())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(2));
    Map<String, Object> badType = vehicle(plate());
    badType.put("vehicleType", "SPACESHIP");
    postJson("/api/v1/vehicles", badType, driver()).andExpect(status().isBadRequest());
  }
}
