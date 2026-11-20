package io.breland.bbagent.server.blobstore;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

public class InMemoryBlobStore implements BlobStore {
  private final Cache<String, Map<String, String>> blobsByConversation =
      CacheBuilder.newBuilder().expireAfterWrite(READ_WINDOW).maximumSize(100).build();
  private final Cache<String, byte[]> cadencePayloads =
      CacheBuilder.newBuilder()
          .expireAfterAccess(Duration.ofDays(30))
          .maximumWeight(64L * 1024 * 1024)
          .weigher((String key, byte[] value) -> value.length)
          .build();

  @Override
  public void storeBlob(String conversationId, String id, String blob) {
    if (StringUtils.isAnyBlank(conversationId, id) || blob == null) {
      return;
    }
    blobsByConversation
        .asMap()
        .computeIfAbsent(conversationId, key -> new ConcurrentHashMap<>())
        .put(id, blob);
  }

  @Override
  public String getBlob(String conversationId, String id) {
    if (StringUtils.isAnyBlank(conversationId, id)) {
      return null;
    }
    Map<String, String> conversationBlobs = blobsByConversation.getIfPresent(conversationId);
    return conversationBlobs == null ? null : conversationBlobs.get(id);
  }

  @Override
  public void storeCadencePayload(String digest, boolean scheduled, byte[] payload) {
    cadencePayloads.put(digest, payload.clone());
  }

  @Override
  public byte[] getCadencePayload(String digest, boolean scheduled) {
    byte[] payload = cadencePayloads.getIfPresent(digest);
    return payload == null ? null : payload.clone();
  }
}
