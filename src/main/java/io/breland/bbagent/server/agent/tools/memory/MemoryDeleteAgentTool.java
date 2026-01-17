package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class MemoryDeleteAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_delete";
  private final Mem0Client mem0Client;

  @Schema(description = "Delete a stored memory.")
  public record MemoryDeleteRequest(
      @Schema(
              description = "ID of the memory to delete.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("memory_id")
          String memoryId) {}

  public MemoryDeleteAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a stored memory about a user or conversation by memory_id",
        jsonSchema(MemoryDeleteRequest.class),
        false,
        (context, args) -> {
          if (!mem0Client.isConfigured()) {
            return "not configured";
          }
          MemoryDeleteRequest request =
              context.getMapper().convertValue(args, MemoryDeleteRequest.class);
          String memoryId = request.memoryId();
          if (memoryId == null || memoryId.isBlank()) {
            return "missing memory_id";
          }
          boolean deleted = mem0Client.deleteMemory(memoryId);
          return deleted ? "deleted" : "failed";
        });
  }
}
