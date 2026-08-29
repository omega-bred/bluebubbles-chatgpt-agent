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
          client.listTools().tools().stream()
              .map(McpSchema.Tool::name)
              .anyMatch(properties.getToolName()::equals);
      if (!hasConfiguredTool) {
        throw new IllegalStateException("Wallart MCP server did not advertise the configured tool");
      }

      CallToolResult result =
          client.callTool(new CallToolRequest(properties.getToolName(), Map.of("prompt", prompt)));
      List<String> textContent =
          result.content() == null
              ? List.of()
              : result.content().stream()
                  .filter(TextContent.class::isInstance)
                  .map(TextContent.class::cast)
                  .map(TextContent::text)
                  .toList();
      if (Boolean.TRUE.equals(result.isError())) {
        throw new IllegalStateException(
            textContent.isEmpty()
                ? "Wallart MCP tool returned an error"
                : "Wallart MCP tool returned an error: " + String.join("; ", textContent));
      }

      Map<String, Object> output = new LinkedHashMap<>();
      output.put("status", "submitted");
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
}
