package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

public class MemoryUpdateAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_update";
  private final Mem0Client mem0Client;
  private final @Nullable MemoryScopeResolver scopeResolver;

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
    this(mem0Client, null);
  }

  public MemoryUpdateAgentTool(Mem0Client mem0Client, @Nullable MemoryScopeResolver scopeResolver) {
    this.mem0Client = mem0Client;
    this.scopeResolver = scopeResolver;
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
          if (scopeResolver == null) {
            return "memory scope unavailable";
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
          String canonicalScope = scopeResolver.primaryScope(context).orElse(null);
          if (canonicalScope == null) {
            return context.message() != null && context.message().isGroup()
                ? "group memory is not enabled"
                : "memory scope unavailable";
          }
          if (!scopeResolver.ownsMemory(canonicalScope, memoryId)) {
            return "memory does not belong to the current scope";
          }
          String normalizedText = text.trim();
          boolean updated = mem0Client.updateMemory(memoryId, normalizedText, null);
          if (!updated) {
            return "failed";
          }
          scopeResolver.updateOwnership(canonicalScope, memoryId, normalizedText);
          return "updated";
        });
  }
}
