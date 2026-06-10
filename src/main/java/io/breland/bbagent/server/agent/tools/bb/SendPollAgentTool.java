package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
      @Schema(
              description =
                  "Poll options. Provide at least two non-empty options, either as strings or objects with text.")
          JsonNode options) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create and send an iMessage poll in the current BlueChat conversation. Use this when the user asks to make, create, start, or send a poll.",
        jsonSchema(parametersSchema()),
        false,
        (context, args) -> {
          if (context.message() == null || !context.message().isBlueBubblesTransport()) {
            return "polls are only supported on BlueChat/iMessage conversations";
          }
          SendPollRequest request = context.getMapper().convertValue(args, SendPollRequest.class);
          if (StringUtils.isBlank(request.title())) {
            return "missing title";
          }
          List<BBHttpClientWrapper.PollSendOption> options = pollOptions(request.options());
          if (options.size() < 2) {
            return "missing options";
          }
          if (!context.consumeMessageResponseQuota()) {
            return "skipped: quota exceeded or outdated workflow";
          }
          String chatGuid = context.message().chatGuid();
          JsonNode data = bbHttpClientWrapper.sendPollJson(chatGuid, request.title(), options);
          context.recordAssistantTurn("Sent poll: " + request.title());
          return data.toString();
        });
  }

  private static Map<String, Object> parametersSchema() {
    Map<String, Object> pollOptionObject =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "text",
                Map.of("type", "string", "description", "Option text."),
                "option_identifier",
                Map.of("type", "string", "description", "Optional stable option identifier.")),
            "required",
            List.of("text"),
            "additionalProperties",
            false);
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "title",
            Map.of("type", "string", "description", "Poll title/question."),
            "options",
            Map.of(
                "type",
                "array",
                "minItems",
                2,
                "description",
                "Poll options. Each option may be a string or an object with text.",
                "items",
                Map.of(
                    "anyOf",
                    List.of(
                        Map.of("type", "string", "description", "Option text."),
                        pollOptionObject)))),
        "required",
        List.of("title", "options"),
        "additionalProperties",
        false);
  }

  private static List<BBHttpClientWrapper.PollSendOption> pollOptions(JsonNode optionsNode) {
    if (optionsNode == null || optionsNode.isNull() || !optionsNode.isArray()) {
      return List.of();
    }
    List<BBHttpClientWrapper.PollSendOption> options = new ArrayList<>();
    for (JsonNode optionNode : optionsNode) {
      BBHttpClientWrapper.PollSendOption option = pollOption(optionNode);
      if (option != null) {
        options.add(option);
      }
    }
    return options;
  }

  private static BBHttpClientWrapper.PollSendOption pollOption(JsonNode optionNode) {
    if (optionNode == null || optionNode.isNull()) {
      return null;
    }
    String text;
    String optionIdentifier = null;
    if (optionNode.isTextual()) {
      text = optionNode.asText();
    } else if (optionNode.isObject()) {
      text = textValue(optionNode, "text");
      optionIdentifier =
          StringUtils.defaultIfBlank(
              textValue(optionNode, "option_identifier"),
              textValue(optionNode, "optionIdentifier"));
    } else {
      text = optionNode.asText(null);
    }
    if (StringUtils.isBlank(text)) {
      return null;
    }
    return new BBHttpClientWrapper.PollSendOption(
        text.trim(), StringUtils.trimToNull(optionIdentifier));
  }

  private static String textValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText(null);
  }
}
