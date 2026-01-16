package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;

public class MemoryDeleteAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_delete";
  private final Mem0Client mem0Client;

  public MemoryDeleteAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a stored memory about a user or conversation by memory_id",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("memory_id", Map.of("type", "string")),
                "required",
                List.of("memory_id"))),
        false,
        (context, args) -> {
          if (!mem0Client.isConfigured()) {
            return "not configured";
          }
          String memoryId = getRequired(args, "memory_id");
          boolean deleted = mem0Client.deleteMemory(memoryId);
          return deleted ? "deleted" : "failed";
        });
  }
}
