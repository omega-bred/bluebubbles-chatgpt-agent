package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class ReadPollAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "read_poll";

  private final BBHttpClientWrapper bbHttpClientWrapper;

  public ReadPollAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Schema(description = "Read an iMessage poll and its current votes.")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReadPollRequest(
      @Schema(
              description =
                  "Poll message GUID. Defaults to the current poll or thread root when possible.")
          @JsonProperty("message_guid")
          String messageGuid) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Read an iMessage poll by message GUID and return its title, options, and current votes. Use this when asked about poll results, votes, choices, or the current state of a poll.",
        jsonSchema(ReadPollRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null || !context.message().isBlueBubblesTransport()) {
            return "polls are only supported on BlueChat/iMessage conversations";
          }
          ReadPollRequest request = context.getMapper().convertValue(args, ReadPollRequest.class);
          String messageGuid =
              BlueBubblesPollSupport.normalizeMessageGuid(
                  StringUtils.defaultIfBlank(request.messageGuid(), pollGuid(context.message())));
          if (StringUtils.isBlank(messageGuid)) {
            messageGuid = latestPollGuid(context.message());
          }
          if (StringUtils.isBlank(messageGuid)) {
            return "missing message_guid and no recent poll was found in this conversation";
          }
          try {
            JsonNode data = bbHttpClientWrapper.readPollJson(messageGuid);
            return data.toString();
          } catch (RuntimeException e) {
            return "could not read poll "
                + messageGuid
                + ": BlueBubbles returned an error for the poll state request";
          }
        });
  }

  private static String pollGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (StringUtils.isNotBlank(message.associatedMessageGuid())) {
      return BlueBubblesPollSupport.normalizeMessageGuid(message.associatedMessageGuid());
    }
    if (StringUtils.isNotBlank(message.replyToGuid())) {
      return BlueBubblesPollSupport.normalizeMessageGuid(message.replyToGuid());
    }
    if (StringUtils.isNotBlank(message.threadOriginatorGuid())) {
      return BlueBubblesPollSupport.normalizeMessageGuid(message.threadOriginatorGuid());
    }
    if (BlueBubblesPollSupport.isPollBundle(message.balloonBundleId())) {
      return BlueBubblesPollSupport.normalizeMessageGuid(message.messageGuid());
    }
    return null;
  }

  private String latestPollGuid(IncomingMessage message) {
    if (message == null || StringUtils.isBlank(message.chatGuid())) {
      return null;
    }
    try {
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages =
          bbHttpClientWrapper.getMessagesInChat(message.chatGuid());
      if (messages == null) {
        return null;
      }
      return messages.stream()
          .map(ReadPollAgentTool::pollGuid)
          .filter(StringUtils::isNotBlank)
          .findFirst()
          .orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String pollGuid(ApiV1ChatChatGuidMessageGet200ResponseDataInner message) {
    if (message == null || !BlueBubblesPollSupport.isPollBundle(message.getBalloonBundleId())) {
      return null;
    }
    String associatedGuid =
        BlueBubblesPollSupport.normalizeMessageGuid(message.getAssociatedMessageGuid());
    if (StringUtils.isNotBlank(associatedGuid)) {
      return associatedGuid;
    }
    return BlueBubblesPollSupport.normalizeMessageGuid(message.getGuid());
  }
}
