package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.BBMessageAgent.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.*;
import java.util.function.Predicate;

public class SendTextAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_text";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public SendTextAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Send a text reply via iMessage.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "chatGuid",
                    Map.of("type", "string"),
                    "message",
                    Map.of("type", "string"),
                    "selectedMessageGuid",
                    Map.of("type", "string"),
                    "partIndex",
                    Map.of("type", "integer", "minimum", 0),
                    "method",
                    Map.of("type", "string", "enum", List.of("apple-script", "private-api"))),
                "required",
                List.of("chatGuid", "message"))),
        false,
        (context, args) -> {
          ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
          String chatGuid = getRequired(args, "chatGuid");
          String message = getRequired(args, "message");
          request.setChatGuid(chatGuid);
          request.setMessage(message);
          request.setTempGuid(UUID.randomUUID().toString());

          Optional.of(args)
              .map(a -> a.get("selectedMessageGuid"))
              .filter(Predicate.not(JsonNode::isNull))
              .filter(JsonNode::isTextual)
              .map(JsonNode::asText)
              .ifPresent(request::selectedMessageGuid);

          Optional.of(args)
              .map(a -> a.get("partIndex"))
              .filter(Predicate.not(JsonNode::isNull))
              .filter(JsonNode::isNumber)
              .map(JsonNode::asInt)
              .ifPresent(request::partIndex);

          Optional.of(args)
              .map(a -> a.get("method"))
              .filter(Predicate.not(JsonNode::isNull))
              .filter(JsonNode::isTextual)
              .map(JsonNode::asText)
              .ifPresent(request::method);

          bbHttpClientWrapper.sendTextDirect(request);
          context.recordAssistantTurn(message);
          return "sent";
        });
  }
}
