package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

public class MemorySaveAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "memory_save";
  private final Mem0Client mem0Client;

  @Schema(description = "Save a memory for the current user or conversation.")
  public record MemorySaveRequest(
      @Schema(description = "Memory text to store.", requiredMode = Schema.RequiredMode.REQUIRED)
          String memory) {}

  public MemorySaveAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Save memories about the current user or conversation. Any time you discover useful information about a user - you should persist it with this tool. Information like the user's name, preferences, or general tone/vibe are appropriate to store here.",
        jsonSchema(MemorySaveRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          String userIdOrGroupChatId = AgentTool.resolveUserIdOrGroupChatId(message);
          if (userIdOrGroupChatId == null || userIdOrGroupChatId.isBlank()) {
            return "no sender";
          }
          if (!mem0Client.isConfigured()) {
            return "not configured";
          }
          MemorySaveRequest request =
              context.getMapper().convertValue(args, MemorySaveRequest.class);
          String memory = request.memory();
          if (memory == null || memory.isBlank()) {
            return "no memory";
          }
          String groupChatSenderId =
              AgentTool.isGroupMessage(message) ? AgentTool.getSenderId(message) : null;
          boolean saved =
              mem0Client.addMemory(
                  userIdOrGroupChatId, memory.trim(), buildMetadata(message), null);
          return saved ? "saved" : "failed";
        });
  }

  private Map<String, Object> buildMetadata(IncomingMessage message) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source", "bbagent");
    if (message == null) {
      return metadata;
    }
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      metadata.put("chat_guid", message.chatGuid());
    }
    if (message.messageGuid() != null && !message.messageGuid().isBlank()) {
      metadata.put("message_guid", message.messageGuid());
    }
    if (message.isGroup() != null) {
      metadata.put("is_group", AgentTool.isGroupMessage(message));
    }
    if (message.sender() != null && !message.sender().isBlank()) {
      metadata.put("sender", message.sender());
    }
    return metadata;
  }
}
