package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class SendPollAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_poll";

  private final BBHttpClientWrapper bbHttpClientWrapper;

  public SendPollAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Schema(description = "Send an iMessage poll in the current BlueChat conversation.")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SendPollRequest(
      @Schema(description = "Poll title/question.", requiredMode = Schema.RequiredMode.REQUIRED)
          String title,
      @Schema(description = "Poll options. Provide at least two non-empty options.")
          List<PollOptionRequest> options,
      @Schema(
              description =
                  "Optional conversation/chat GUID. Defaults to the current conversation.")
          @JsonProperty("conversation_id")
          String conversationId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PollOptionRequest(
      @Schema(description = "Option text.", requiredMode = Schema.RequiredMode.REQUIRED)
          String text,
      @Schema(description = "Optional stable option identifier.") @JsonProperty("option_identifier")
          String optionIdentifier) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create and send an iMessage poll in the current BlueChat conversation. Use this when the user asks to make, create, start, or send a poll.",
        jsonSchema(SendPollRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null || !context.message().isBlueBubblesTransport()) {
            return "polls are only supported on BlueChat/iMessage conversations";
          }
          SendPollRequest request = context.getMapper().convertValue(args, SendPollRequest.class);
          if (StringUtils.isBlank(request.title())) {
            return "missing title";
          }
          List<BBHttpClientWrapper.PollSendOption> options =
              request.options() == null
                  ? List.of()
                  : request.options().stream()
                      .filter(option -> option != null && StringUtils.isNotBlank(option.text()))
                      .map(
                          option ->
                              new BBHttpClientWrapper.PollSendOption(
                                  option.text(), option.optionIdentifier()))
                      .toList();
          if (options.size() < 2) {
            return "missing options";
          }
          if (!context.consumeMessageResponseQuota()) {
            return "skipped: quota exceeded or outdated workflow";
          }
          String chatGuid =
              StringUtils.defaultIfBlank(request.conversationId(), context.message().chatGuid());
          JsonNode data = bbHttpClientWrapper.sendPollJson(chatGuid, request.title(), options);
          context.recordAssistantTurn("Sent poll: " + request.title());
          return data.toString();
        });
  }
}
