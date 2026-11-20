package io.breland.bbagent.server.blobstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

final class RustFsBlobStore implements BlobStore, AutoCloseable {
  private static final String PREFIX = "blobs/v1/";
  private static final String CADENCE_PAYLOADS = "cadence/v1/turns/";
  private static final String SCHEDULED_PAYLOADS = "cadence/v1/scheduled/";

  private final S3Client client;
  private final String bucket;

  RustFsBlobStore(S3Client client, String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  void configureExpiry() {
    // S3 lifecycle expiration uses whole days. Reads still use the shorter blob window.
    client.putBucketLifecycleConfiguration(
        request ->
            request
                .bucket(bucket)
                .lifecycleConfiguration(
                    config ->
                        config.rules(
                            expiry("expire-bluechat-blobs", PREFIX, 1),
                            expiry("expire-bluechat-cadence-turns", CADENCE_PAYLOADS, 7))));
  }

  private static LifecycleRule expiry(String id, String prefix, int days) {
    return LifecycleRule.builder()
        .id(id)
        .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
        .status(ExpirationStatus.ENABLED)
        .expiration(LifecycleExpiration.builder().days(days).build())
        .build();
  }

  @Override
  public void storeBlob(String conversationId, String id, String blob) {
    if (StringUtils.isAnyBlank(conversationId, id) || blob == null) {
      return;
    }
    client.putObject(
        request ->
            request
                .bucket(bucket)
                .key(key(conversationId, id))
                .contentType("application/octet-stream"),
        RequestBody.fromString(blob));
  }

  @Override
  public String getBlob(String conversationId, String id) {
    if (StringUtils.isAnyBlank(conversationId, id)) {
      return null;
    }
    try (var object =
        client.getObject(request -> request.bucket(bucket).key(key(conversationId, id)))) {
      if (!object.response().lastModified().plus(READ_WINDOW).isAfter(Instant.now())) {
        object.abort();
        return null;
      }
      return new String(object.readAllBytes(), StandardCharsets.UTF_8);
    } catch (NoSuchKeyException e) {
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read image blob from RustFS", e);
    }
  }

  @Override
  public void storeCadencePayload(String digest, boolean scheduled, byte[] payload) {
    client.putObject(
        request ->
            request
                .bucket(bucket)
                .key(cadenceKey(digest, scheduled))
                .contentType("application/octet-stream"),
        RequestBody.fromBytes(payload));
  }

  @Override
  public byte[] getCadencePayload(String digest, boolean scheduled) {
    try {
      return client
          .getObjectAsBytes(request -> request.bucket(bucket).key(cadenceKey(digest, scheduled)))
          .asByteArray();
    } catch (NoSuchKeyException e) {
      return null;
    }
  }

  private static String cadenceKey(String digest, boolean scheduled) {
    return (scheduled ? SCHEDULED_PAYLOADS : CADENCE_PAYLOADS) + digest;
  }

  @Override
  public void close() {
    client.close();
  }

  private static String key(String conversationId, String id) {
    return PREFIX + DigestUtils.sha256Hex(conversationId) + "/" + DigestUtils.sha256Hex(id);
  }
}
