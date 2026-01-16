package io.breland.bbagent.server.agent.tools.assistant;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.Map;

public class AssistantNameAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "assistant_name_tool";

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Store, set, or forget a user's name for global use across chats. Only store a name after the user explicitly agrees and mention you will use it across any chats the user is present in.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "action",
                    Map.of("type", "string", "enum", java.util.List.of("store", "set", "forget")),
                    "name",
                    Map.of("type", "string")))),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.sender() == null || message.sender().isBlank()) {
            return "no sender";
          }
          String action = getOptionalText(args, "action");
          if (action == null || action.isBlank()) {
            action = "store";
          }
          String normalized = action.trim().toLowerCase();
          if ("forget".equals(normalized)
              || "remove".equals(normalized)
              || "delete".equals(normalized)) {
            context.removeGlobalNameForSender(message.sender());
            return "removed name for sender";
          }
          String name = getOptionalText(args, "name");
          if (name == null || name.isBlank()) {
            return "missing name";
          }
          context.setGlobalNameForSender(message.sender(), name);
          return "stored name for sender";
        });
  }
}
