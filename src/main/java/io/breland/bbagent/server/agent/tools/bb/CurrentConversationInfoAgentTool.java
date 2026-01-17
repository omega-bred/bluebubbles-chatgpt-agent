package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
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
          Chat response = bbHttpClientWrapper.getConversationInfo(message.chatGuid());
          if (response == null) {
            return "not found";
          }
          Map<String, Object> result = new LinkedHashMap<>();

          result.put("display_name", response.getDisplayName());
          List<Map<String, String>> participants = new ArrayList<>();
          response
              .getParticipants()
              .forEach(
                  participant -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("handle", participant.getAddress());
                    entry.put("country", participant.getCountry());
                    participants.add(entry);
                    // TODO: build a genric db that (respecting user prefs) support globally getting
                    // the user name
                  });
          result.put("participants", participants);

          try {
            return bbHttpClientWrapper.getObjectMapper().writeValueAsString(result);
          } catch (Exception e) {
            return response.toString();
          }
        });
  }
}
