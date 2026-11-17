package com.parksmart.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTest {

  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
  private static final UUID OWNER = UUID.randomUUID();

  private static Vehicle vehicle(String plate, String region) {
    return Vehicle.register(
        UUID.randomUUID(), OWNER, plate, region, VehicleType.SEDAN, " Maruti ", "", null, true, T0);
  }

  @Test
  void plate_and_region_are_normalized() {
    Vehicle v = vehicle(" mh-12 ab 1234 ", "in-mh");
    assertThat(v.licensePlate()).isEqualTo("MH12AB1234");
    assertThat(v.plateRegion()).isEqualTo("IN-MH");
    assertThat(v.make()).isEqualTo("Maruti");
    assertThat(v.model()).isNull();
    assertThat(v.isNew()).isTrue();
  }

  @Test
  void bad_plates_and_regions_are_rejected() {
    assertThatThrownBy(() -> vehicle("A", "IN"))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("invalid-licensePlate");
    assertThatThrownBy(() -> vehicle("MH12$1", "IN"))
        .extracting("code")
        .isEqualTo("invalid-licensePlate");
    assertThatThrownBy(() -> vehicle("MH12AB1234", "INDIA"))
        .extracting("code")
        .isEqualTo("invalid-plateRegion");
  }

  @Test
  void removing_clears_primary_and_blocks_further_changes() {
    Vehicle v = vehicle("MH12AB1234", "IN-MH");
    assertThat(v.remove(T0)).isTrue();
    assertThat(v.primary()).isFalse();
    assertThat(v.isActive()).isFalse();
    assertThatThrownBy(() -> v.markPrimary(T0)).extracting("code").isEqualTo("vehicle-removed");
    assertThatThrownBy(() -> v.updateDetails(VehicleType.SUV, null, null, null, T0))
        .extracting("code")
        .isEqualTo("vehicle-removed");
  }

  @Test
  void ownership() {
    Vehicle v = vehicle("MH12AB1234", "IN-MH");
    assertThat(v.isOwnedBy(OWNER)).isTrue();
    assertThat(v.isOwnedBy(UUID.randomUUID())).isFalse();
  }
}
