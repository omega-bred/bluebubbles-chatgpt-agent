package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;

public class MemoryUpdateAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_update";
  private final Mem0Client mem0Client;

  public MemoryUpdateAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update a stored memory about a user or conversation.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "memory_id", Map.of("type", "string"),
                    "memory", Map.of("type", "string")),
                "required",
                List.of("memory_id", "memory"))),
        false,
        (context, args) -> {
          if (!mem0Client.isConfigured()) {
            return "not configured";
          }
          String memoryId = getRequired(args, "memory_id");
          String text = getRequired(args, "memory");
          boolean updated = mem0Client.updateMemory(memoryId, text, null);
          return updated ? "updated" : "failed";
        });
  }
}
