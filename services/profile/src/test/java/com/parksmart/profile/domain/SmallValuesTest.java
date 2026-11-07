package com.parksmart.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmallValuesTest {

  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

  @Test
  void notification_channels_default_to_enabled_and_in_app_stays_on() {
    NotificationPreferences prefs =
        new NotificationPreferences(UUID.randomUUID(), Map.of(NotificationChannel.SMS, false));
    assertThat(prefs.isEnabled(NotificationChannel.EMAIL)).isTrue();
    assertThat(prefs.isEnabled(NotificationChannel.SMS)).isFalse();
    prefs.set(NotificationChannel.EMAIL, false);
    assertThat(prefs.isEnabled(NotificationChannel.EMAIL)).isFalse();
    assertThatThrownBy(() -> prefs.set(NotificationChannel.IN_APP, false))
        .extracting("code")
        .isEqualTo("channel-required");
    assertThat(prefs.asMap()).hasSize(4);
  }

  @Test
  void payout_token_is_validated_and_only_a_hint_is_shown() {
    PayoutAccount account =
        PayoutAccount.add(
            UUID.randomUUID(), UUID.randomUUID(), ProcessorType.FAKE, "acct_123abcd", T0);
    assertThat(account.verified()).isFalse();
    assertThat(account.tokenHint()).isEqualTo("…abcd");
    assertThat(account.toString()).doesNotContain("acct_123abcd");
    assertThatThrownBy(() ->
            PayoutAccount.add(UUID.randomUUID(), UUID.randomUUID(), ProcessorType.FAKE, "a b", T0))
        .extracting("code")
        .isEqualTo("invalid-processorToken");
  }

  @Test
  void image_signatures() {
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    byte[] webp = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};
    assertThat(PhotoContentType.JPEG.matchesSignature(jpeg)).isTrue();
    assertThat(PhotoContentType.PNG.matchesSignature(png)).isTrue();
    assertThat(PhotoContentType.WEBP.matchesSignature(webp)).isTrue();
    assertThat(PhotoContentType.PNG.matchesSignature(jpeg)).isFalse();
    assertThat(PhotoContentType.WEBP.matchesSignature(new byte[] {'R', 'I', 'F', 'F'})).isFalse();
    assertThat(PhotoContentType.JPEG.matchesSignature(new byte[0])).isFalse();
    assertThat(PhotoContentType.fromMediaType("IMAGE/PNG")).contains(PhotoContentType.PNG);
    assertThat(PhotoContentType.fromMediaType("image/gif")).isEmpty();
  }

  @Test
  void upload_slots_are_scoped_to_the_account_and_single_use() {
    UUID account = UUID.randomUUID();
    PhotoUpload upload =
        PhotoUpload.start(
            UUID.randomUUID(), account, PhotoContentType.PNG, 1000, T0, Duration.ofMinutes(10));
    assertThat(upload.objectKey()).startsWith("uploads/" + account + "/");
    assertThat(upload.photoKey()).isEqualTo("profiles/" + account + "/photo-" + upload.id());
    upload.confirm(T0.plusSeconds(60));
    assertThatThrownBy(() -> upload.confirm(T0.plusSeconds(61)))
        .extracting("code")
        .isEqualTo("upload-confirmed");
  }

  @Test
  void expired_or_oversized_uploads_are_rejected() {
    PhotoUpload upload =
        PhotoUpload.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PhotoContentType.PNG,
            1000,
            T0,
            Duration.ofMinutes(10));
    assertThatThrownBy(() -> upload.confirm(T0.plus(Duration.ofMinutes(10))))
        .extracting("code")
        .isEqualTo("upload-expired");
    assertThatThrownBy(() ->
            PhotoUpload.start(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PhotoContentType.PNG,
                PhotoUpload.MAX_BYTES + 1,
                T0,
                Duration.ofMinutes(10)))
        .extracting("code")
        .isEqualTo("invalid-contentLength");
  }
}
