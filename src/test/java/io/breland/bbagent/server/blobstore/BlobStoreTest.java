package io.breland.bbagent.server.blobstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BlobStoreTest {

  @Test
  void ignoresBlankKeysAndNullBlobs() {
    BlobStore store = new BlobStore();

    store.storeBlob(" ", "id", "ignored");
    store.storeBlob("chat", " ", "ignored");
    store.storeBlob("chat", "id", null);

    assertNull(store.getBlob("chat", "id"));
  }

  @Test
  void storesAndReadsBlobForValidKeys() {
    BlobStore store = new BlobStore();

    store.storeBlob("chat", "id", "value");

    assertEquals("value", store.getBlob("chat", "id"));
    assertNull(store.getBlob(" ", "id"));
    assertNull(store.getBlob("chat", " "));
  }
}
