package io.breland.bbagent.server.agent.transport.bb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

public final class BlueBubblesHandleAddress {
  private BlueBubblesHandleAddress() {}

  public static @Nullable String from(@Nullable Object handle) {
    if (handle instanceof CharSequence address) {
      return StringUtils.trimToNull(address.toString());
    }
    if (handle instanceof JsonNode node) {
      return node.isTextual() ? StringUtils.trimToNull(node.asText()) : from(node.get("address"));
    }
    if (handle instanceof Map<?, ?> object) {
      return from(object.get("address"));
    }
    return null;
  }
}
