package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhotoUploadIT extends IntegrationTest {

  private JsonNode startUpload(User user, String type, int size) throws Exception {
    return body(
        postJson(
                "/api/v1/profiles/me/photo/upload-urls",
                Map.of("contentType", type, "contentLength", size),
                user)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.requiredHeaders.Content-Type").value(type)));
  }

  private String objectKey(JsonNode slot) {
    return jdbc.sql("SELECT object_key FROM profile_photo_uploads WHERE id = :id")
        .param("id", UUID.fromString(slot.get("uploadId").asText()))
        .query(String.class)
        .single();
  }

  private String confirmPath(JsonNode slot) {
    return "/api/v1/profiles/me/photo/uploads/" + slot.get("uploadId").asText() + "/confirm";
  }

  @Test
  void upload_confirm_sets_photo_and_publishes_has_photo() throws Exception {
    User user = driver();
    JsonNode slot = startUpload(user, "image/png", 2048);
    String key = objectKey(slot);
    assertThat(key).startsWith("profiles/" + user.id() + "/");
    storage.put(key, "image/png", FakePhotoStorage.png(2048));

    post(confirmPath(slot), user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasPhoto").value(true))
        .andExpect(jsonPath("$.photoUrl").value("https://photos.parksmart.test/" + key));

    List<JsonNode> events = events("ProfileUpdated", "accountId", user.id());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("payload").get("hasPhoto").asBoolean()).isTrue();

    // Confirming twice is a conflict.
    post(confirmPath(slot), user)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-confirmed"));
  }

  @Test
  void a_new_photo_replaces_and_deletes_the_old_object() throws Exception {
    User user = driver();
    JsonNode first = startUpload(user, "image/png", 100);
    String firstKey = objectKey(first);
    storage.put(firstKey, "image/png", FakePhotoStorage.png(100));
    post(confirmPath(first), user).andExpect(status().isOk());

    JsonNode second = startUpload(user, "image/png", 200);
    storage.put(objectKey(second), "image/png", FakePhotoStorage.png(200));
    post(confirmPath(second), user).andExpect(status().isOk());

    assertThat(storage.deleted()).contains(firstKey);
    assertThat(storage.exists(objectKey(second))).isTrue();
  }

  @Test
  void delete_removes_photo_and_publishes_has_photo_false() throws Exception {
    User user = driver();
    JsonNode slot = startUpload(user, "image/png", 100);
    storage.put(objectKey(slot), "image/png", FakePhotoStorage.png(100));
    post(confirmPath(slot), user).andExpect(status().isOk());

    delete("/api/v1/profiles/me/photo", user).andExpect(status().isNoContent());
    delete("/api/v1/profiles/me/photo", user).andExpect(status().isNoContent());

    get("/api/v1/profiles/me", user).andExpect(jsonPath("$.hasPhoto").value(false));
    List<JsonNode> events = events("ProfileUpdated", "accountId", user.id());
    assertThat(events).hasSize(2);
    assertThat(events.get(1).get("payload").get("hasPhoto").asBoolean()).isFalse();
    assertThat(storage.deleted()).contains(objectKey(slot));
  }

  @Test
  void confirm_rejects_missing_wrong_size_and_fake_images() throws Exception {
    User user = driver();
    JsonNode slot = startUpload(user, "image/png", 100);
    post(confirmPath(slot), user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-missing"));

    storage.put(objectKey(slot), "image/png", FakePhotoStorage.png(99));
    post(confirmPath(slot), user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-mismatch"));

    storage.put(objectKey(slot), "image/png", new byte[100]); // right size, not a PNG
    post(confirmPath(slot), user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-mismatch"));

    get("/api/v1/profiles/me", user).andExpect(jsonPath("$.hasPhoto").value(false));
  }

  @Test
  void another_accounts_upload_is_not_found() throws Exception {
    User owner = driver();
    JsonNode slot = startUpload(owner, "image/png", 100);
    storage.put(objectKey(slot), "image/png", FakePhotoStorage.png(100));

    post(confirmPath(slot), driver()).andExpect(status().isNotFound());
    get("/api/v1/profiles/me", owner).andExpect(jsonPath("$.hasPhoto").value(false));
  }

  @Test
  void bad_requests_and_pending_limit() throws Exception {
    User user = driver();
    postJson(
            "/api/v1/profiles/me/photo/upload-urls",
            Map.of("contentType", "image/gif", "contentLength", 10),
            user)
        .andExpect(status().isBadRequest());
    postJson(
            "/api/v1/profiles/me/photo/upload-urls",
            Map.of("contentType", "image/png", "contentLength", 5_242_881),
            user)
        .andExpect(status().isBadRequest());

    for (int i = 0; i < 5; i++) {
      startUpload(user, "image/jpeg", 10);
    }
    postJson(
            "/api/v1/profiles/me/photo/upload-urls",
            Map.of("contentType", "image/png", "contentLength", 10),
            user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/too-many-pending-uploads"));
  }
}
