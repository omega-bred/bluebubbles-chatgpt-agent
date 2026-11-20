package io.breland.bbagent.server.blobstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class BlobStoreConfigurationTest {
  private final BlobStoreConfiguration configuration = new BlobStoreConfiguration();
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void ignoresBucketInfoOutsideProduction() throws IOException {
    Path bucketInfo = Files.writeString(temp.resolve("BucketInfo"), "an unreadable configuration");

    assertThat(configuration.blobStore(new MockEnvironment(), mapper, bucketInfo.toString()))
        .isInstanceOf(InMemoryBlobStore.class);
  }

  @Test
  void usesMemoryInProductionWithoutAMountedSecret() {
    assertThat(configuration.blobStore(production(), mapper, ""))
        .isInstanceOf(InMemoryBlobStore.class);
    assertThat(configuration.blobStore(production(), mapper, temp.resolve("BucketInfo").toString()))
        .isInstanceOf(InMemoryBlobStore.class);
  }

  @Test
  void rejectsMalformedProductionCredentialsWithoutExposingTheirContents() throws IOException {
    Path bucketInfo = Files.writeString(temp.resolve("BucketInfo"), "example-private-key");

    assertThatThrownBy(() -> configuration.blobStore(production(), mapper, bucketInfo.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to read the RustFS COSI BucketInfo file")
        .hasNoCause();
  }

  private static MockEnvironment production() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    return environment;
  }
}
