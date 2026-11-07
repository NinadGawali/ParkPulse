package com.parksmart.profile.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class S3StorageConfig {

  @Bean(destroyMethod = "close")
  S3Client s3Client(StorageProperties properties) {
    S3ClientBuilder builder =
        S3Client.builder()
            .region(Region.of(properties.region()))
            .credentialsProvider(credentials(properties))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .forcePathStyle(properties.pathStyleAccess());
    if (properties.endpointUri() != null) {
      builder.endpointOverride(properties.endpointUri());
    }
    return builder.build();
  }

  @Bean(destroyMethod = "close")
  S3Presigner s3Presigner(StorageProperties properties) {
    S3Presigner.Builder builder =
        S3Presigner.builder()
            .region(Region.of(properties.region()))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.pathStyleAccess())
                    .build());
    if (properties.endpointUri() != null) {
      builder.endpointOverride(properties.endpointUri());
    }
    return builder.build();
  }

  private static AwsCredentialsProvider credentials(StorageProperties properties) {
    return properties.hasStaticCredentials()
        ? StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()))
        : DefaultCredentialsProvider.builder().build();
  }
}
