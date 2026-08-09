package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.lang.Nullable;

public class MemoryGetAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_get";
  private final Mem0Client mem0Client;
  private final @Nullable MemoryScopeResolver scopeResolver;

  @Schema(description = "Query memory for the current user or conversation.")
  public record MemoryGetRequest(
      @Schema(description = "Query text to search memories.") String query) {}

  public MemoryGetAgentTool(Mem0Client mem0Client) {
    this(mem0Client, null);
  }

  public MemoryGetAgentTool(Mem0Client mem0Client, @Nullable MemoryScopeResolver scopeResolver) {
    this.mem0Client = mem0Client;
    this.scopeResolver = scopeResolver;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Query memory for the current user or conversation. Use this tool any time the user asks a"
            + " question or when memory might answer their question or if personal details or prior"
            + " context might enhance your ability to answer. The memory accepts a natural language"
            + " query. Use this tool to try and resolve inputs for other tools that rely on"
            + " personal details or preferences (examples are things like names, relationships,"
            + " location, user preferences, etc). ",
        jsonSchema(MemoryGetRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (scopeResolver == null) {
            return "memory scope unavailable";
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
          Optional<String> canonicalScope = scopeResolver.primaryScope(context);
          List<Mem0Client.StoredMemory> memories = List.of();
          if (canonicalScope.isPresent()) {
            memories = mem0Client.searchMemories(canonicalScope.get(), query);
          }
          boolean legacy = false;
          if (memories.isEmpty()) {
            Optional<String> legacyScope = scopeResolver.legacyReadScope(context);
            if (legacyScope.isPresent()) {
              memories = mem0Client.searchMemories(legacyScope.get(), query);
              legacy = !memories.isEmpty();
            }
          }
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
            entry.put("legacy", legacy);
            if (!entry.isEmpty()) {
              formatted.add(entry);
            }
          }
          result.put("memories", formatted);
          return ToolJson.stringify(this.mem0Client.getObjectMapper(), result, result.toString());
        });
  }
}
