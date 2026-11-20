package io.breland.bbagent.server.blobstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
class BlobStoreConfiguration {
  private static final Logger log = LoggerFactory.getLogger(BlobStoreConfiguration.class);

  @Bean
  BlobStore blobStore(
      Environment environment,
      ObjectMapper objectMapper,
      @Value("${bbagent.blob-store.bucket-info-path:}") String bucketInfoPath) {
    if (!environment.matchesProfiles("prod") || StringUtils.isBlank(bucketInfoPath)) {
      return new InMemoryBlobStore();
    }
    Path path = Path.of(bucketInfoPath);
    if (Files.notExists(path)) {
      log.warn("RustFS COSI credentials are absent. Using the in-memory blob store.");
      return new InMemoryBlobStore();
    }

    BucketInfo info = loadBucketInfo(path, objectMapper);
    S3Client client =
        S3Client.builder()
            .endpointOverride(info.endpoint())
            .region(Region.of(info.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(info.accessKeyId(), info.secretAccessKey())))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(3))
                    .socketTimeout(Duration.ofSeconds(10)))
            .overrideConfiguration(
                config ->
                    config
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(12)))
            .build();
    RustFsBlobStore store = new RustFsBlobStore(client, info.bucket());
    try {
      store.configureExpiry();
      log.info("Using the RustFS COSI blob store");
      return store;
    } catch (RuntimeException e) {
      store.close();
      throw e;
    }
  }

  private static BucketInfo loadBucketInfo(Path path, ObjectMapper objectMapper) {
    JsonNode info;
    try (var bucketInfo = Files.newInputStream(path)) {
      info = objectMapper.readTree(bucketInfo);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read the RustFS COSI BucketInfo file");
    }
    if (info == null) {
      throw new IllegalStateException("RustFS COSI BucketInfo is empty");
    }
    JsonNode spec = info.path("spec");
    JsonNode s3 = spec.path("secretS3");
    String bucket = spec.path("bucketName").asText();
    String endpoint = s3.path("endpoint").asText();
    String region = s3.path("region").asText();
    String accessKeyId = s3.path("accessKeyID").asText();
    String secretAccessKey = s3.path("accessSecretKey").asText();
    if (StringUtils.isAnyBlank(bucket, endpoint, region, accessKeyId, secretAccessKey)) {
      throw new IllegalStateException(
          "RustFS COSI BucketInfo is missing the bucket or S3 credentials");
    }
    URI endpointUri;
    try {
      endpointUri = URI.create(endpoint);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("RustFS COSI BucketInfo has an invalid S3 endpoint");
    }
    if (endpointUri.getHost() == null
        || !("http".equals(endpointUri.getScheme()) || "https".equals(endpointUri.getScheme()))
        || endpointUri.getUserInfo() != null) {
      throw new IllegalStateException("RustFS COSI BucketInfo requires an HTTP(S) S3 endpoint");
    }
    return new BucketInfo(bucket, endpointUri, region, accessKeyId, secretAccessKey);
  }

  private record BucketInfo(
      String bucket, URI endpoint, String region, String accessKeyId, String secretAccessKey) {}
}
