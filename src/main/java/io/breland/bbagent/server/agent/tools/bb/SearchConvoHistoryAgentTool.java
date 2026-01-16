package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SearchConvoHistoryAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "search_convo_history";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public SearchConvoHistoryAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search recent message history for the current conversation. The tool only supports case insensitive substring searches when searching for text - so you may need to use it multiple times with variations of the target text. It is limited to 1 month of history.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "query", Map.of("type", "string"),
                    "limit", Map.of("type", "integer"),
                    "offset", Map.of("type", "integer")))),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
            return "no chat";
          }
          String query = getOptionalText(args, "query");
          Integer limit =
              args.has("limit") && args.get("limit").isNumber() ? args.get("limit").asInt() : null;
          Integer offset =
              args.has("offset") && args.get("offset").isNumber()
                  ? args.get("offset").asInt()
                  : null;
          List<Message> bbMessages =
              bbHttpClientWrapper.searchConversationHistory(
                  message.chatGuid(), query, limit, offset);
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
          try {
            return bbHttpClientWrapper.getObjectMapper().writeValueAsString(result);
          } catch (Exception e) {
            log.error(e.getMessage(), e);
            return "failed to serialize messages";
          }
        });
  }
}
