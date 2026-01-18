package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class MemoryUpdateAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_update";
  private final Mem0Client mem0Client;

  @Schema(description = "Update a stored memory.")
  public record MemoryUpdateRequest(
      @Schema(
              description = "ID of the memory to update.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("memory_id")
          String memoryId,
      @Schema(description = "Updated memory text.", requiredMode = Schema.RequiredMode.REQUIRED)
          String memory) {}

  public MemoryUpdateAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update a stored memory about a user or conversation.",
        jsonSchema(MemoryUpdateRequest.class),
        false,
        (context, args) -> {
          if (!mem0Client.isConfigured()) {
            return "not configured";
          }
          MemoryUpdateRequest request =
              context.getMapper().convertValue(args, MemoryUpdateRequest.class);
          String memoryId = request.memoryId();
          String text = request.memory();
          if (memoryId == null || memoryId.isBlank()) {
            return "missing memory_id";
          }
          if (text == null || text.isBlank()) {
            return "missing memory";
          }
          boolean updated = mem0Client.updateMemory(memoryId, text, null);
          return updated ? "updated" : "failed";
        });
  }
}
