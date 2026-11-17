package com.parksmart.listing.infrastructure.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code parksmart.storage.*}: S3 on AWS (credentials from the task role), an S3-compatible
 * container locally (endpoint + static keys + path-style access).
 *
 * @param endpoint override for S3-compatible storage; null on AWS
 * @param region AWS region (any value for local storage)
 * @param bucket bucket holding profile photos
 * @param publicBaseUrl base URL photos are served from (CloudFront on AWS), no trailing slash
 * @param pathStyleAccess true for S3-compatible containers
 * @param accessKey static access key (local only); null uses the default AWS credential chain
 * @param secretKey static secret key (local only)
 * @param createBucket create the bucket and a public-read policy at startup (local only)
 */
@ConfigurationProperties("parksmart.storage")
public record StorageProperties(
    String endpoint,
    String region,
    String bucket,
    String publicBaseUrl,
    boolean pathStyleAccess,
    String accessKey,
    String secretKey,
    boolean createBucket) {

  public StorageProperties {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("parksmart.storage.bucket is required");
    }
    if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
      throw new IllegalArgumentException("parksmart.storage.public-base-url is required");
    }
    publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    if (region == null || region.isBlank()) {
      region = "ap-south-1";
    }
  }

  /** The endpoint override, or null when talking to AWS S3 directly. */
  URI endpointUri() {
    return endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint);
  }

  @Override
  public String toString() {
    return "StorageProperties[endpoint="
        + endpoint
        + ", bucket="
        + bucket
        + ", publicBaseUrl="
        + publicBaseUrl
        + ", staticCredentials="
        + hasStaticCredentials()
        + "]";
  }

  boolean hasStaticCredentials() {
    return accessKey != null && !accessKey.isBlank();
  }
}
