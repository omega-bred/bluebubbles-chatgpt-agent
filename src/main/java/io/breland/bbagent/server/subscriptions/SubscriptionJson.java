package io.breland.bbagent.server.subscriptions;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

final class SubscriptionJson {
  private SubscriptionJson() {}

  static @Nullable String firstNonBlank(@Nullable String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  static @Nullable String firstText(@Nullable JsonNode node, String... fieldNames) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value != null && !value.isNull()) {
        String text = value.isTextual() ? value.asText() : value.asText(null);
        if (StringUtils.isNotBlank(text)) {
          return text;
        }
      }
    }
    return null;
  }

  static @Nullable JsonNode findObject(@Nullable JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode direct = node.get(fieldName);
    if (direct != null && direct.isObject()) {
      return direct;
    }
    if (node.isObject() || node.isArray()) {
      for (JsonNode value : node) {
        JsonNode found = findObject(value, fieldName);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
