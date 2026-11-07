package com.parksmart.profile.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.parksmart.profile.application.port.PhotoStorage.PresignedUpload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The real S3 adapter against the same S3-compatible image docker-compose uses. */
class S3PhotoStorageIT {

  static final GenericContainer<?> STORAGE =
      new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.4"))
          .withEnv("RUSTFS_ACCESS_KEY", "testaccesskey")
          .withEnv("RUSTFS_SECRET_KEY", "testsecretkey123")
          .withExposedPorts(9000)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofMinutes(2));

  static S3Client s3;
  static S3Presigner presigner;
  static S3PhotoStorage storage;

  @BeforeAll
  static void start() {
    STORAGE.start();
    StorageProperties properties =
        new StorageProperties(
            "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(9000),
            "us-east-1",
            "test-photos",
            "http://cdn.test/test-photos/",
            true,
            "testaccesskey",
            "testsecretkey123",
            true);
    S3StorageConfig config = new S3StorageConfig();
    s3 = config.s3Client(properties);
    presigner = config.s3Presigner(properties);
    storage = new S3PhotoStorage(s3, presigner, properties);
    storage.createBucketIfConfigured();
  }

  @AfterAll
  static void stop() {
    s3.close();
    presigner.close();
    STORAGE.stop();
  }

  @Test
  void presigned_put_then_stat_read_head_and_delete() throws Exception {
    byte[] png = Arrays.copyOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    String key = "uploads/acc/upload-1";
    PresignedUpload upload =
        storage.presignPut(key, "image/png", png.length, Duration.ofMinutes(5));
    assertThat(upload.requiredHeaders()).containsKey("Content-Type");

    HttpRequest.Builder put =
        HttpRequest.newBuilder(URI.create(upload.url()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(png));
    upload
        .requiredHeaders()
        .forEach((name, value) -> {
          if (!name.equalsIgnoreCase("content-length")) {
            put.header(name, value);
          }
        });
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

    assertThat(storage.stat(key))
        .hasValueSatisfying(o -> {
          assertThat(o.contentType()).isEqualTo("image/png");
          assertThat(o.contentLength()).isEqualTo(64);
        });
    assertThat(storage.readHead(key, 8)).hasSize(8).startsWith((byte) 0x89, (byte) 'P');
    assertThat(storage.publicUrl(key)).isEqualTo("http://cdn.test/test-photos/" + key);

    storage.copy(key, key + "-copy");
    assertThat(storage.stat(key + "-copy"))
        .hasValueSatisfying(o -> assertThat(o.contentType()).isEqualTo("image/png"));
    storage.delete(key + "-copy");

    storage.delete(key);
    assertThat(storage.stat(key)).isEmpty();
    storage.delete(key); // missing objects are ignored
  }

  @Test
  void a_presigned_url_rejects_a_different_content_type() throws Exception {
    String key = "uploads/acc/upload-2";
    PresignedUpload upload = storage.presignPut(key, "image/png", 4, Duration.ofMinutes(5));
    HttpRequest put =
        HttpRequest.newBuilder(URI.create(upload.url()))
            .header("Content-Type", "text/html")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3, 4}))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(put, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(storage.stat(key)).isEmpty();
  }
}
