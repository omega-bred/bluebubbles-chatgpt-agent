package io.breland.bbagent.server.agent.tools.coder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CoderToolResultFormatter {

  private final ObjectMapper objectMapper;

  CoderToolResultFormatter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String format(McpSchema.CallToolResult result) throws JsonProcessingException {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("is_error", result != null && Boolean.TRUE.equals(result.isError()));
    if (result != null && result.structuredContent() != null) {
      response.put("structured_content", result.structuredContent());
    }
    List<Map<String, Object>> content = new ArrayList<>();
    if (result != null && result.content() != null) {
      for (McpSchema.Content item : result.content()) {
        content.add(contentToMap(item));
      }
    }
    response.put("content", content);
    return objectMapper.writeValueAsString(response);
  }

  private Map<String, Object> contentToMap(McpSchema.Content content) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", content.type());
    if (content instanceof McpSchema.TextContent textContent) {
      item.put("text", textContent.text());
    } else if (content instanceof McpSchema.ImageContent imageContent) {
      item.put("mime_type", imageContent.mimeType());
      item.put("data", "[base64 image data omitted]");
    } else if (content instanceof McpSchema.AudioContent audioContent) {
      item.put("mime_type", audioContent.mimeType());
      item.put("data", "[base64 audio data omitted]");
    } else {
      item.put("value", content.toString());
    }
    return item;
  }
}
