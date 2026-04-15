package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class ToolJson {

  private ToolJson() {}

  public static String stringify(ObjectMapper mapper, Object value, String fallback) {
    if (mapper == null) {
      return fallback;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return fallback;
    }
  }
}
