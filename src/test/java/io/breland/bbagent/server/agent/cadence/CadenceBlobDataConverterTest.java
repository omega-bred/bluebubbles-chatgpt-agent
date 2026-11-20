package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JsonDataConverter;
import io.breland.bbagent.server.agent.cadence.CadenceBlobDataConverter.ScheduledMemoValue;
import io.breland.bbagent.server.blobstore.InMemoryBlobStore;
import org.junit.jupiter.api.Test;

class CadenceBlobDataConverterTest {
  private final com.uber.cadence.converter.DataConverter inline = JsonDataConverter.getInstance();
  private final InMemoryBlobStore blobs = new InMemoryBlobStore();
  private final CadenceBlobDataConverter writer = new CadenceBlobDataConverter(inline, blobs);
  private final CadenceBlobDataConverter reader = new CadenceBlobDataConverter(inline, blobs);

  @Test
  void readsOldInlinePayloadsAndOffloadsTheWholeSerializedArgumentArray() {
    assertThat(writer.toData("legacy")).isEqualTo(inline.toData("legacy"));
    assertThat(reader.fromData(inline.toData("legacy"), String.class, String.class))
        .isEqualTo("legacy");
    String value = "é".repeat(40_000);
    byte[] reference = writer.toData(value, value, 42);

    assertThat(reference).hasSizeLessThan(200);
    assertThat(reference).isEqualTo(writer.toData(value, value, 42));
    assertThat(reader.fromDataArray(reference, String.class, String.class, int.class))
        .containsExactly(value, value, 42);
  }

  @Test
  void offloadsPayloadsThatWouldExceedCadencesDefaultLimit() {
    String large = "日".repeat(800_000);
    byte[] reference = writer.toData(large);

    assertThat(reference).hasSizeLessThan(200);
    assertThat(reader.fromData(reference, String.class, String.class)).isEqualTo(large);
    var otherStore = new CadenceBlobDataConverter(inline, new InMemoryBlobStore());
    assertThatThrownBy(() -> otherStore.fromData(reference, String.class, String.class))
        .isInstanceOf(DataConverterException.class);
  }

  @Test
  void retainsOversizedScheduledMemoValuesAndPreservesTheirWireTypes() {
    assertThat(writer.toData(new ScheduledMemoValue("short"))).isEqualTo(inline.toData("short"));
    String large = "scheduled task ".repeat(20_000);
    byte[] reference = writer.toData(new ScheduledMemoValue(large));

    assertThat(reference).hasSizeLessThan(200).isNotEqualTo(writer.toData(large));
    assertThat(reader.fromData(reference, Object.class, Object.class)).isEqualTo(large);
  }
}
