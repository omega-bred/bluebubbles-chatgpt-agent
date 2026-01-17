package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MemoryGetAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_get";
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final Mem0Client mem0Client;

  @Schema(description = "Query memory for the current user or conversation.")
  public record MemoryGetRequest(
      @Schema(description = "Query text to search memories.") String query) {}

  public MemoryGetAgentTool(Mem0Client mem0Client) {
    this.mem0Client = mem0Client;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Query memory for the current user or conversation. Use it any time the user asks a question or when memory might answer their question or if personal details or prior context might enhance your ability to answer. The memory accepts a natural language query. Use this tool to try and resolve inputs for other tools that rely on personal details or preferences (examples are things like names, relationships, location, user preferences, etc). ",
        jsonSchema(MemoryGetRequest.class),
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

          MemoryGetRequest request = context.getMapper().convertValue(args, MemoryGetRequest.class);
          String query = request.query();
          if (query == null || query.isBlank()) {
            query = message.text();
          }
          if (query == null || query.isBlank()) {
            query = "What do you know about me?";
          }
          List<Mem0Client.StoredMemory> memories =
              mem0Client.searchMemories(userIdOrGroupChatId, query);
          if (memories.isEmpty()) {
            return "not found";
          }
          Map<String, Object> result = new LinkedHashMap<>();
          List<Map<String, Object>> formatted = new java.util.ArrayList<>();
          for (Mem0Client.StoredMemory memory : memories) {
            if (memory == null) {
              continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            if (memory.memoryId() != null && !memory.memoryId().isBlank()) {
              entry.put("memory_id", memory.memoryId());
            }
            if (memory.memory() != null && !memory.memory().isBlank()) {
              entry.put("memory", memory.memory());
            }
            if (!entry.isEmpty()) {
              formatted.add(entry);
            }
          }
          result.put("memories", formatted);
          try {
            return objectMapper.writeValueAsString(result);
          } catch (Exception e) {
            return result.toString();
          }
        });
  }
}
