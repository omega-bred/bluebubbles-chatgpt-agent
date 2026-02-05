package io.breland.bbagent.server.blobstore;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BlobStore {

  private final Cache<String, Map<String, String>> blobsByConversation =
      CacheBuilder.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(100).build();

  public void storeBlob(String conversationId, String id, String blob) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (id == null || id.isBlank()) {
      return;
    }
    if (blob == null) {
      return;
    }
    blobsByConversation
        .asMap()
        .computeIfAbsent(conversationId, key -> new ConcurrentHashMap<>())
        .put(id, blob);
  }

  public String getBlob(String conversationId, String id) {
    if (conversationId == null || conversationId.isBlank()) {
      return null;
    }
    if (id == null || id.isBlank()) {
      return null;
    }
    Map<String, String> conversationBlobs = blobsByConversation.getIfPresent(conversationId);
    if (conversationBlobs == null) {
      return null;
    }
    return conversationBlobs.get(id);
  }
}
