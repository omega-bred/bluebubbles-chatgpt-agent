package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

public class MemoryDeleteAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "memory_delete";
  private final Mem0Client mem0Client;
  private final @Nullable MemoryScopeResolver scopeResolver;

  @Schema(description = "Delete a stored memory.")
  public record MemoryDeleteRequest(
      @Schema(
              description = "ID of the memory to delete.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("memory_id")
          String memoryId) {}

  public MemoryDeleteAgentTool(Mem0Client mem0Client, @Nullable MemoryScopeResolver scopeResolver) {
    this.mem0Client = mem0Client;
    this.scopeResolver = scopeResolver;
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
          if (scopeResolver == null) {
            return "memory scope unavailable";
          }
          MemoryDeleteRequest request =
              context.getMapper().convertValue(args, MemoryDeleteRequest.class);
          String memoryId = request.memoryId();
          if (memoryId == null || memoryId.isBlank()) {
            return "missing memory_id";
          }
          String canonicalScope = scopeResolver.primaryScope(context).orElse(null);
          if (canonicalScope == null) {
            return context.message() != null && context.message().isGroup()
                ? "group memory is not enabled"
                : "memory scope unavailable";
          }
          if (scopeResolver.isReadOnlyMemory(canonicalScope, memoryId)) {
            return "collective group memories are read-only";
          }
          if (!scopeResolver.ownsMemory(canonicalScope, memoryId)) {
            return "memory does not belong to the current scope";
          }
          boolean deleted = mem0Client.deleteMemory(memoryId);
          if (!deleted) {
            return "failed";
          }
          scopeResolver.removeOwnership(canonicalScope, memoryId);
          return "deleted";
        });
  }
}
