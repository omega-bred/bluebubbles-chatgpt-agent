package io.breland.bbagent.server.blobstore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BlobStore {

  private final Map<String, Map<String, String>> blobsByConversation = new ConcurrentHashMap<>();

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
    Map<String, String> conversationBlobs = blobsByConversation.get(conversationId);
    if (conversationBlobs == null) {
      return null;
    }
    return conversationBlobs.get(id);
  }
}
