package com.parksmart.listing.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Object storage for photos (S3 on AWS, an S3-compatible container locally). Clients upload
 * directly with a presigned PUT; the service never proxies image bytes.
 */
public interface PhotoStorage {

  /** A presigned PUT bound to the key, content type and length. */
  PresignedUpload presignPut(
      String objectKey, String contentType, long contentLength, Duration ttl);

  /** Metadata of a stored object, or empty if it does not exist. */
  Optional<StoredObject> stat(String objectKey);

  /** The first {@code bytes} bytes of the object (fewer if it is shorter). */
  byte[] readHead(String objectKey, int bytes);

  /** Server-side copy within the bucket (metadata included). */
  void copy(String fromKey, String toKey);

  /** Deletes the object; missing objects are ignored. */
  void delete(String objectKey);

  /** Public URL the object is served from (CloudFront on AWS). */
  String publicUrl(String objectKey);

  record PresignedUpload(String url, Map<String, String> requiredHeaders, Instant expiresAt) {}

  record StoredObject(String contentType, long contentLength) {}
}
