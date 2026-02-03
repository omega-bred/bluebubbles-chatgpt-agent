package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;

public class SendReactionAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_reaction";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  @Schema(description = "Send a reaction to a specific message.")
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

  public SendReactionAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

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
          ApiV1MessageReactPostRequest apiRequest = new ApiV1MessageReactPostRequest();
          apiRequest.setChatGuid(chatGuid);
          apiRequest.setSelectedMessageGuid(selectedMessageGuid);
          apiRequest.setReaction(reaction);

          Optional.ofNullable(toolRequest.partIndex()).ifPresent(apiRequest::setPartIndex);

          bbHttpClientWrapper.sendReactionDirect(apiRequest);
          context.recordAssistantTurn("[reaction: " + reaction + "]");
          return "sent";
        });
  }
}
