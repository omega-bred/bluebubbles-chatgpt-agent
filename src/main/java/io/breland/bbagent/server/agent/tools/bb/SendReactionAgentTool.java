package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class SendReactionAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_reaction";

  @Schema(description = "Send a reaction to a specific message.")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SendReactionRequest(
      @Schema(
              description = "Chat GUID containing the message.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String chatGuid,
      @Schema(
              description = "Message GUID to react to.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String selectedMessageGuid,
      @Schema(
              description = "Reaction value.",
              allowableValues = {
                "love",
                "like",
                "dislike",
                "laugh",
                "emphasize",
                "question",
                "-love",
                "-like",
                "-dislike",
                "-laugh",
                "-emphasize",
                "-question"
              },
              requiredMode = Schema.RequiredMode.REQUIRED)
          String reaction,
      @Schema(description = "Attachment part index when reacting to a specific part.")
          Integer partIndex) {}

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Send a reaction to a specific message.",
        jsonSchema(SendReactionRequest.class),
        false,
        (context, args) -> {
          SendReactionRequest toolRequest =
              context.getMapper().convertValue(args, SendReactionRequest.class);
          String chatGuid = toolRequest.chatGuid();
          String selectedMessageGuid = toolRequest.selectedMessageGuid();
          String reaction = toolRequest.reaction();
          if (chatGuid == null || chatGuid.isBlank()) {
            return "missing chatGuid";
          }
          if (selectedMessageGuid == null || selectedMessageGuid.isBlank()) {
            return "missing selectedMessageGuid";
          }
          if (reaction == null || reaction.isBlank()) {
            return "missing reaction";
          }
          if (!context.canSendResponses()) {
            return "skipped: outdated workflow";
          }
          boolean sent =
              context.sendReaction(
                  chatGuid, selectedMessageGuid, reaction, toolRequest.partIndex());
          if (!sent) {
            return "reaction unsupported";
          }
          context.recordAssistantTurn("[reaction: " + reaction + "]");
          return "sent";
        });
  }
}
