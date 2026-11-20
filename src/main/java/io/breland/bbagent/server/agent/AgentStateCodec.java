package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class AgentStateCodec {
  private final ObjectMapper mapper;

  public AgentStateCodec(ObjectMapper objectMapper) {
    mapper =
        objectMapper
            .copy()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  public String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize agent state", e);
    }
  }

  public <T> T decode(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not deserialize agent state", e);
    }
  }
}
