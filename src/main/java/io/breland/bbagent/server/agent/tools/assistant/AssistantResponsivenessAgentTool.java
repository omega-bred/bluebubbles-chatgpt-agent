package io.breland.bbagent.server.agent.tools.assistant;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.Map;

public class AssistantResponsivenessAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "assistant_responsiveness_tool";

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Update the assistant responsiveness for this conversation. Use less_responsive to reduce participation or more_responsive to be more active. Use default to reset.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "responsiveness",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        java.util.List.of("less_responsive", "more_responsive", "default"))))),
        false,
        (context, args) -> {
          String responsiveness = getOptionalText(args, "responsiveness");
          if (responsiveness == null || responsiveness.isBlank()) {
            return "missing responsiveness";
          }
          BBMessageAgent.AssistantResponsiveness resolved = resolveVerbosity(responsiveness);
          if (resolved == null) {
            return "invalid responsiveness";
          }
          context.setAssistantResponsiveness(resolved);
          return "updated to " + resolved.name().toLowerCase();
        });
  }

  private BBMessageAgent.AssistantResponsiveness resolveVerbosity(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim().toLowerCase();
    return switch (value) {
      case "less", "less_responsive", "quiet", "silent", "reserved" ->
          BBMessageAgent.AssistantResponsiveness.LESS_RESPONSIVE;
      case "more", "more_responsive", "active", "chatty", "participant" ->
          BBMessageAgent.AssistantResponsiveness.MORE_RESPONSIVE;
      case "default", "normal", "balanced" -> BBMessageAgent.AssistantResponsiveness.DEFAULT;
      default -> null;
    };
  }
}
