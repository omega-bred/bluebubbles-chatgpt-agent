package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import io.swagger.v3.oas.annotations.media.Schema;
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
                  StringUtils.defaultIfBlank(request.messageGuid(), pollGuid(context)));
          if (StringUtils.isBlank(messageGuid)) {
            return "missing message_guid";
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

  private static String pollGuid(ToolContext context) {
    IncomingMessage message = context.message();
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
    return BlueBubblesPollSupport.normalizeMessageGuid(message.messageGuid());
  }
}
