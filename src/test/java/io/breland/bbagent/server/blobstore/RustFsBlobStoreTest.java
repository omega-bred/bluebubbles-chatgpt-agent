package io.breland.bbagent.server.blobstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

class RustFsBlobStoreTest {
  private final S3Client client = mock(S3Client.class, CALLS_REAL_METHODS);
  private final RustFsBlobStore store = new RustFsBlobStore(client, "image-blobs");

  @Test
  void closesExpiredObjectWithoutReadingItsBody() throws IOException {
    GetObjectResponse response =
        GetObjectResponse.builder()
            .lastModified(Instant.now().minus(BlobStore.READ_WINDOW))
            .build();
    ByteArrayInputStream data = mock(ByteArrayInputStream.class);
    doReturn(new ResponseInputStream<>(response, data))
        .when(client)
        .getObject(any(GetObjectRequest.class));

    assertThat(store.getBlob("conversation", "image")).isNull();
    verify(data, atLeastOnce()).close();
    verifyNoMoreInteractions(data);
  }

  @Test
  void propagatesStorageFailuresToTheActivity() {
    doThrow(NoSuchBucketException.builder().statusCode(404).build())
        .when(client)
        .getObject(any(GetObjectRequest.class));

    assertThatThrownBy(() -> store.getBlob("conversation", "image"))
        .isInstanceOf(NoSuchBucketException.class);
  }
}
