package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

public class MemorySaveAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "memory_save";
  private final HindsightClient hindsightClient;
  private final @Nullable MemoryScopeResolver scopeResolver;

  @Schema(description = "Save a memory for the current user or conversation.")
  public record MemorySaveRequest(
      @Schema(description = "Memory text to store.", requiredMode = Schema.RequiredMode.REQUIRED)
          String memory) {}

  public MemorySaveAgentTool(
      HindsightClient hindsightClient, @Nullable MemoryScopeResolver scopeResolver) {
    this.hindsightClient = hindsightClient;
    this.scopeResolver = scopeResolver;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Save durable context or an actionable commitment with concrete future usefulness. "
            + "Omit routine updates, transient details, repetition, and inferred preferences or consent. "
            + "Use the current personal or shared group scope. Processing is asynchronous.",
        jsonSchema(MemorySaveRequest.class),
        false,
        (context, args) -> {
          if (!hindsightClient.isConfigured()) return "not configured";
          IncomingMessage message = context.message();
          if (scopeResolver == null) {
            return "memory scope unavailable";
          }
          String canonicalScope = scopeResolver.primaryScope(context).orElse(null);
          if (canonicalScope == null) {
            return message != null && message.isGroup()
                ? "group memory is not enabled"
                : "memory scope unavailable";
          }
          MemorySaveRequest request =
              context.getMapper().convertValue(args, MemorySaveRequest.class);
          String memory = request.memory();
          if (memory == null || memory.isBlank()) {
            return "no memory";
          }
          String normalizedMemory = memory.trim();
          String id = scopeResolver.save(canonicalScope, normalizedMemory, message);
          return "queued for memory processing; memory_id=" + id;
        });
  }
}
