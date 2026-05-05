package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

public class RenameConversationAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "rename_conversation";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  @Schema(description = "Rename the current conversation.")
  public record RenameConversationRequest(
      @Schema(
              description = "New display name for the conversation.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String name) {}

  public RenameConversationAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Rename the current conversation. Use this tool when the user requests or suggests renaming"
            + " the group, renaming the chat or other common terms for chat",
        jsonSchema(RenameConversationRequest.class),
        false,
        (context, args) -> {
          String chatGuid = context.chatGuid();
          if (chatGuid == null) {
            return "no chat";
          }
          if (!context.isGroupChat()) {
            return "not group";
          }
          RenameConversationRequest request =
              context.getMapper().convertValue(args, RenameConversationRequest.class);
          String displayName = request.name();
          if (displayName == null || displayName.isBlank()) {
            return "missing name";
          }
          boolean success = bbHttpClientWrapper.renameConversation(chatGuid, displayName);
          return success ? "renamed" : "failed";
        });
  }
}
