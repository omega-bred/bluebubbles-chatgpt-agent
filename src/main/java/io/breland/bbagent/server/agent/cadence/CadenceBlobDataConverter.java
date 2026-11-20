package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import io.breland.bbagent.generated.model.CadencePayloadReference;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.blobstore.BlobStore;
import java.lang.reflect.Type;
import java.util.Arrays;
import org.apache.commons.codec.digest.DigestUtils;

final class CadenceBlobDataConverter implements DataConverter {
  // The converter measures the complete serialized argument array. Existing JSON payloads
  // remain readable, and the version marker distinguishes references from inline JSON.
  private static final byte[] REFERENCE_PREFIX = {0, 'B', 'C', 1};
  static final int MAX_INLINE_BYTES = 128 * 1024;

  private final DataConverter delegate;
  private final BlobStore blobs;

  record ScheduledMemoValue(Object value) {}

  CadenceBlobDataConverter(DataConverter delegate, BlobStore blobs) {
    this.delegate = delegate;
    this.blobs = blobs;
  }

  @Override
  public byte[] toData(Object... values) {
    boolean scheduledMemo = false;
    if (values != null && values.length == 1 && values[0] instanceof ScheduledMemoValue memo) {
      scheduledMemo = true;
      values = new Object[] {memo.value()};
    }
    byte[] payload = delegate.toData(values);
    if (payload == null || payload.length <= MAX_INLINE_BYTES) return payload;
    boolean scheduled =
        scheduledMemo
            || Arrays.stream(values)
                .anyMatch(
                    value ->
                        value instanceof CadenceMessageWorkflowRequest request
                            && request.scheduledFor() != null);
    String digest = DigestUtils.sha256Hex(payload);
    blobs.storeCadencePayload(digest, scheduled, payload);
    byte[] reference =
        delegate.toData(new CadencePayloadReference().sha256(digest).scheduled(scheduled));
    byte[] encoded = Arrays.copyOf(REFERENCE_PREFIX, REFERENCE_PREFIX.length + reference.length);
    System.arraycopy(reference, 0, encoded, REFERENCE_PREFIX.length, reference.length);
    return encoded;
  }

  @Override
  public <T> T fromData(byte[] data, Class<T> valueClass, Type valueType) {
    return delegate.fromData(resolve(data), valueClass, valueType);
  }

  @Override
  public Object[] fromDataArray(byte[] data, Type... valueTypes) {
    return delegate.fromDataArray(resolve(data), valueTypes);
  }

  private byte[] resolve(byte[] data) {
    if (data == null
        || data.length <= REFERENCE_PREFIX.length
        || Arrays.mismatch(
                data, 0, REFERENCE_PREFIX.length, REFERENCE_PREFIX, 0, REFERENCE_PREFIX.length)
            != -1) {
      return data;
    }
    var reference =
        delegate.fromData(
            Arrays.copyOfRange(data, REFERENCE_PREFIX.length, data.length),
            CadencePayloadReference.class,
            CadencePayloadReference.class);
    String digest = reference == null ? null : reference.getSha256();
    if (digest == null || !digest.matches("[0-9a-f]{64}") || reference.getScheduled() == null) {
      throw new DataConverterException("Invalid Cadence payload reference", null, null);
    }
    byte[] payload = blobs.getCadencePayload(digest, reference.getScheduled());
    if (payload == null || !digest.equals(DigestUtils.sha256Hex(payload))) {
      throw new DataConverterException(
          "Cadence payload is missing or failed verification", null, null);
    }
    return payload;
  }
}
