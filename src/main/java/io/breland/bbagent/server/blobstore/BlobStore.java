package io.breland.bbagent.server.blobstore;

import java.time.Duration;

public interface BlobStore {
  Duration READ_WINDOW = Duration.ofHours(1);

  void storeBlob(String conversationId, String id, String blob);

  String getBlob(String conversationId, String id);
}
