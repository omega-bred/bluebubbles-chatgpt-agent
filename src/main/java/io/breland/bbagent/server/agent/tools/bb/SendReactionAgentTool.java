package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.BBMessageAgent.*;
import static java.util.function.Predicate.not;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SendReactionAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_reaction";
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public SendReactionAgentTool(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Send a reaction to a specific message.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "chatGuid",
                    Map.of("type", "string"),
                    "selectedMessageGuid",
                    Map.of("type", "string"),
                    "reaction",
                    Map.of("type", "string", "enum", SUPPORTED_REACTIONS),
                    "partIndex",
                    Map.of("type", "integer", "minimum", 0)),
                "required",
                List.of("chatGuid", "selectedMessageGuid", "reaction"))),
        false,
        (context, args) -> {
          String chatGuid = getRequired(args, "chatGuid");
          String selectedMessageGuid = getRequired(args, "selectedMessageGuid");
          String reaction = getRequired(args, "reaction");
          ApiV1MessageReactPostRequest request = new ApiV1MessageReactPostRequest();
          request.setChatGuid(chatGuid);
          request.setSelectedMessageGuid(selectedMessageGuid);
          request.setReaction(reaction);

          Optional.of(args)
              .map(a -> a.get("partIndex"))
              .filter(not(JsonNode::isNull))
              .filter(JsonNode::isNumber)
              .map(JsonNode::asInt)
              .ifPresent(request::setPartIndex);

          bbHttpClientWrapper.sendReactionDirect(request);
          context.recordAssistantTurn("[reaction: " + reaction + "]");
          return "sent";
        });
  }
}
