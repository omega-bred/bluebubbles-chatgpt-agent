package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchConvoHistoryAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "search_convo_history";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  @Schema(description = "Search recent message history for the current conversation.")
  public record SearchConversationHistoryRequest(
      @Schema(description = "Substring query for message text.") String query,
      @Schema(description = "Maximum number of messages to return.") Integer limit,
      @Schema(description = "Offset for pagination.") Integer offset) {}

  public SearchConvoHistoryAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search recent message history for the current conversation. The tool only supports case"
            + " insensitive substring searches when searching for text - so you may need to use it"
            + " multiple times with variations of the target text. It is limited to 1 month of"
            + " history.",
        jsonSchema(SearchConversationHistoryRequest.class),
        false,
        (context, args) -> {
          String chatGuid = context.chatGuid();
          if (chatGuid == null) {
            return "no chat";
          }
          SearchConversationHistoryRequest request =
              context.getMapper().convertValue(args, SearchConversationHistoryRequest.class);
          String query = request.query();
          Integer limit = request.limit();
          Integer offset = request.offset();
          List<Message> bbMessages =
              bbHttpClientWrapper.searchConversationHistory(chatGuid, query, limit, offset);
          if (bbMessages == null) {
            return "not found";
          }
          List<Map<String, Object>> messages = new java.util.ArrayList<>();
          Map<String, Object> result = new LinkedHashMap<>();
          bbMessages.forEach(
              msg -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("guid", msg.getGuid());
                entry.put("text", msg.getText());
                entry.put("dateCreated", msg.getDateCreated());
                entry.put("sender", msg.getHandle());
                messages.add(entry);
              });

          result.put("messages", messages);
          return context.stringify(result, "failed to serialize messages");
        });
  }
}
