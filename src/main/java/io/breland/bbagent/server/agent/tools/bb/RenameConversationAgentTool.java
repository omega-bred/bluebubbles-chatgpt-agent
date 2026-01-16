package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;

public class RenameConversationAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "rename_conversation";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public RenameConversationAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Rename the current conversation. Use this tool when the user requests or suggests renaming the group, renaming the chat or other common terms for chat",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("name", Map.of("type", "string")),
                "required",
                List.of("name"))),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
            return "no chat";
          }
          if (!AgentTool.isGroupMessage(message)) {
            return "not group";
          }
          String displayName = getRequired(args, "name");
          boolean success = bbHttpClientWrapper.renameConversation(message.chatGuid(), displayName);
          return success ? "renamed" : "failed";
        });
  }
}
