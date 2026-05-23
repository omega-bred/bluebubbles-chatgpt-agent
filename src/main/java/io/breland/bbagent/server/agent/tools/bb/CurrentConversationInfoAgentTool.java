package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CurrentConversationInfoAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "current_conversation_info";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  @Schema(description = "No-argument request for current conversation info.")
  public record CurrentConversationInfoRequest() {}

  public CurrentConversationInfoAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Fetch metadata about the current conversation (participants, display name, last message).",
        jsonSchema(CurrentConversationInfoRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
            return "no chat";
          }
          JsonNode response = bbHttpClientWrapper.getConversationInfoJson(message.chatGuid());
          if (response == null) {
            return "not found";
          }
          Map<String, Object> result = new LinkedHashMap<>();

          putText(result, "guid", response.get("guid"));
          putText(result, "display_name", response.get("displayName"));
          putText(result, "chat_identifier", response.get("chatIdentifier"));
          putText(result, "last_addressed_handle", response.get("lastAddressedHandle"));
          List<Map<String, String>> participants = new ArrayList<>();
          JsonNode participantNodes = response.get("participants");
          if (participantNodes != null && participantNodes.isArray()) {
            participantNodes.forEach(
                participant -> {
                  Map<String, String> entry = new LinkedHashMap<>();
                  putParticipantText(entry, "handle", participant.get("address"));
                  putParticipantText(entry, "country", participant.get("country"));
                  participants.add(entry);
                });
          }
          result.put("participants", participants);

          return ToolJson.stringify(
              bbHttpClientWrapper.getObjectMapper(), result, response.toString());
        });
  }

  private static void putParticipantText(Map<String, String> result, String key, JsonNode value) {
    if (value != null && !value.isNull()) {
      result.put(key, value.asText());
    }
  }

  private static void putText(Map<String, Object> result, String key, JsonNode value) {
    if (value != null && !value.isNull()) {
      result.put(key, value.asText());
    }
  }
}
