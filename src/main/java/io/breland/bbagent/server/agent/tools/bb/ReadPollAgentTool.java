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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
          List<String> messageGuids = pollGuidCandidates(context.message(), request.messageGuid());
          if (messageGuids.isEmpty()) {
            return "missing message_guid and no recent poll was found in this conversation";
          }
          List<String> attemptedGuids = new ArrayList<>();
          for (String messageGuid : messageGuids) {
            try {
              attemptedGuids.add(messageGuid);
              JsonNode data = bbHttpClientWrapper.readPollJson(messageGuid);
              return data.toString();
            } catch (RuntimeException e) {
              // Keep trying; reply targets and poll update items can point at non-root messages.
            }
          }
          return "could not read poll; BlueBubbles returned an error for the poll state request. Tried message_guid values: "
              + String.join(", ", attemptedGuids);
        });
  }

  private List<String> pollGuidCandidates(IncomingMessage message, String requestedMessageGuid) {
    Set<String> candidates = new LinkedHashSet<>();
    String requestedGuid = BlueBubblesPollSupport.normalizeMessageGuid(requestedMessageGuid);
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> recentMessages = recentMessages(message);
    addCandidate(candidates, requestedGuid);
    addMatchingHistoryPollCandidates(candidates, recentMessages, requestedGuid);
    addContextCandidates(candidates, message);
    recentMessages.forEach(historyMessage -> addPollGuidCandidates(candidates, historyMessage));
    return List.copyOf(candidates);
  }

  private static void addContextCandidates(Set<String> candidates, IncomingMessage message) {
    if (message == null) {
      return;
    }
    if (BlueBubblesPollSupport.isPollBundle(message.balloonBundleId())) {
      addCandidate(candidates, message.associatedMessageGuid());
      addCandidate(candidates, message.messageGuid());
    }
    if (StringUtils.isNotBlank(message.replyToGuid())) {
      addCandidate(candidates, message.replyToGuid());
    }
    if (StringUtils.isNotBlank(message.threadOriginatorGuid())) {
      addCandidate(candidates, message.threadOriginatorGuid());
    }
  }

  private static void addCandidate(Set<String> candidates, String messageGuid) {
    String normalized = BlueBubblesPollSupport.normalizeMessageGuid(messageGuid);
    if (StringUtils.isNotBlank(normalized)) {
      candidates.add(normalized);
    }
  }

  private List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> recentMessages(
      IncomingMessage message) {
    if (message == null || StringUtils.isBlank(message.chatGuid())) {
      return List.of();
    }
    try {
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages =
          bbHttpClientWrapper.getMessagesInChat(message.chatGuid());
      if (messages == null) {
        return List.of();
      }
      return messages;
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static void addMatchingHistoryPollCandidates(
      Set<String> candidates,
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages,
      String requestedGuid) {
    if (StringUtils.isBlank(requestedGuid) || messages == null) {
      return;
    }
    messages.stream()
        .filter(message -> historyMessageMatchesGuid(message, requestedGuid))
        .forEach(message -> addPollGuidCandidates(candidates, message));
  }

  private static boolean historyMessageMatchesGuid(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner message, String requestedGuid) {
    if (message == null || StringUtils.isBlank(requestedGuid)) {
      return false;
    }
    return requestedGuid.equals(BlueBubblesPollSupport.normalizeMessageGuid(message.getGuid()))
        || requestedGuid.equals(
            BlueBubblesPollSupport.normalizeMessageGuid(message.getAssociatedMessageGuid()));
  }

  private static void addPollGuidCandidates(
      Set<String> candidates, ApiV1ChatChatGuidMessageGet200ResponseDataInner message) {
    if (message == null || !BlueBubblesPollSupport.isPollBundle(message.getBalloonBundleId())) {
      return;
    }
    addCandidate(candidates, message.getAssociatedMessageGuid());
    addCandidate(candidates, message.getGuid());
  }
}
