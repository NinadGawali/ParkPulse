package com.parksmart.profile.infrastructure.storage;

import com.parksmart.profile.application.port.PhotoStorage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** {@link PhotoStorage} on S3 or any S3-compatible store. */
@Component
class S3PhotoStorage implements PhotoStorage {

  private static final Logger log = LoggerFactory.getLogger(S3PhotoStorage.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final StorageProperties properties;

  S3PhotoStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
    this.s3 = s3;
    this.presigner = presigner;
    this.properties = properties;
  }

  @Override
  public PresignedUpload presignPut(
      String objectKey, String contentType, long contentLength, Duration ttl) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .build();
    PresignedPutObjectRequest presigned =
        presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> header : presigned.signedHeaders().entrySet()) {
      if (!header.getKey().equalsIgnoreCase("host")) {
        headers.put(canonical(header.getKey()), String.join(",", header.getValue()));
      }
    }
    return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
  }

  /** content-type -> Content-Type (the SDK reports signed headers in lower case). */
  private static String canonical(String name) {
    StringBuilder out = new StringBuilder(name.length());
    boolean upper = true;
    for (char c : name.toCharArray()) {
      out.append(upper ? Character.toUpperCase(c) : c);
      upper = c == '-';
    }
    return out.toString();
  }

  @Override
  public Optional<StoredObject> stat(String objectKey) {
    try {
      HeadObjectResponse head = s3.headObject(r -> r.bucket(properties.bucket()).key(objectKey));
      return Optional.of(new StoredObject(head.contentType(), head.contentLength()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public byte[] readHead(String objectKey, int bytes) {
    ResponseBytes<GetObjectResponse> head =
        s3.getObjectAsBytes(
            r -> r.bucket(properties.bucket()).key(objectKey).range("bytes=0-" + (bytes - 1)));
    return head.asByteArray();
  }

  @Override
  public void copy(String fromKey, String toKey) {
    s3.copyObject(r ->
        r.sourceBucket(properties.bucket())
            .sourceKey(fromKey)
            .destinationBucket(properties.bucket())
            .destinationKey(toKey));
  }

  @Override
  public void delete(String objectKey) {
    s3.deleteObject(r -> r.bucket(properties.bucket()).key(objectKey));
  }

  @Override
  public String publicUrl(String objectKey) {
    return properties.publicBaseUrl() + "/" + objectKey;
  }

  /** Local only: create the bucket and allow anonymous reads of profile photos. */
  @EventListener(ApplicationReadyEvent.class)
  void createBucketIfConfigured() {
    if (!properties.createBucket()) {
      return;
    }
    String bucket = properties.bucket();
    try {
      try {
        s3.headBucket(r -> r.bucket(bucket));
      } catch (NoSuchBucketException e) {
        s3.createBucket(r -> r.bucket(bucket));
        log.info("Created bucket {}", bucket);
      }
      String policy =
          """
          {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
          "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/profiles/*"]}]}\
          """
              .formatted(bucket);
      s3.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
    } catch (RuntimeException e) {
      log.warn("Could not prepare bucket {}: {}", bucket, e.toString());
    }
  }
}
