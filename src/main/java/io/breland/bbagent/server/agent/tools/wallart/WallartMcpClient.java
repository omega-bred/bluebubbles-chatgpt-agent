package io.breland.bbagent.server.agent.tools.wallart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class WallartMcpClient {
  private final WallartMcpProperties properties;
  private final ObjectMapper objectMapper;

  public WallartMcpClient(WallartMcpProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public String showNewArt(String prompt) {
    return textResult(
        callTool(
            properties.getToolName(),
            StringUtils.isBlank(prompt) ? Map.of() : Map.of("prompt", prompt)),
        "submitted");
  }

  public McpSchema.ImageContent getCurrentArt() {
    CallToolResult result = callTool("getCurrentArt", Map.of());
    List<McpSchema.ImageContent> images =
        result.content() == null
            ? List.of()
            : result.content().stream()
                .filter(McpSchema.ImageContent.class::isInstance)
                .map(McpSchema.ImageContent.class::cast)
                .toList();
    if (images.size() != 1) {
      throw new IllegalStateException("Wallart did not return exactly one current image");
    }
    return images.getFirst();
  }

  public String showImage(Map<String, Object> image) {
    return textResult(callTool("showImage", Map.of("image", image)), "submitted");
  }

  public String composeArt(String prompt, List<Map<String, Object>> images) {
    return textResult(
        callTool("composeArt", Map.of("prompt", prompt, "images", images)), "submitted");
  }

  public String getArtStatus(String workflowId) {
    return textResult(callTool("getArtStatus", Map.of("workflowId", workflowId)), "checked");
  }

  private CallToolResult callTool(String toolName, Map<String, Object> arguments) {
    JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(properties.getBaseUrl())
            .endpoint(properties.getEndpoint())
            .jsonMapper(mcpJsonMapper)
            .build();
    try (McpSyncClient client =
        McpClient.sync(transport)
            .clientInfo(new Implementation("BlueChat wallart bridge", "1.0.0"))
            .initializationTimeout(properties.getRequestTimeout())
            .requestTimeout(properties.getRequestTimeout())
            .build()) {
      client.initialize();
      boolean hasConfiguredTool =
          client.listTools().tools().stream().map(McpSchema.Tool::name).anyMatch(toolName::equals);
      if (!hasConfiguredTool) {
        throw new IllegalStateException("Wallart MCP server did not advertise the configured tool");
      }

      CallToolResult result = client.callTool(new CallToolRequest(toolName, arguments));
      if (result == null || Boolean.TRUE.equals(result.isError())) {
        // Do not include remote output: it can echo a request containing private image bytes.
        throw new IllegalStateException("Wallart MCP tool returned an error");
      }
      return result;
    }
  }

  private String textResult(CallToolResult result, String status) {
    List<String> textContent =
        result.content() == null
            ? List.of()
            : result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .toList();
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("status", status);
    output.put("content", textContent);
    if (result.structuredContent() != null) {
      output.put("structured_content", result.structuredContent());
    }
    try {
      return objectMapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize wallart MCP response", e);
    }
  }
}
