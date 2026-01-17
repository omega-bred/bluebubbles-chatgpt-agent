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
        "Send a text reply via iMessage. You may optionally apply an iMessage effect sparingly (e.g. happy_birthday for birthday wishes).",
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
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "Use this argument to reply directly to a message in a thread - pass the messageGuid to reply to. Always use this when the original message is in a thread."),
                    "effect",
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional iMessage effect to use sparingly. Example: happy_birthday.",
                        "enum",
                        List.of(
                            "slam",
                            "gentle",
                            "invisible",
                            "loud",
                            "confetti",
                            "echo",
                            "fireworks",
                            "happy_birthday",
                            "heart",
                            "love",
                            "lasers",
                            "shooting_star",
                            "sparkles",
                            "spotlight")),
                    "partIndex",
                    Map.of("type", "integer", "minimum", 0)),
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
              .map(a -> a.get("effect"))
              .filter(Predicate.not(JsonNode::isNull))
              .filter(JsonNode::isTextual)
              .map(JsonNode::asText)
              .map(
                  effectName -> {
                    // https://docs.rs/imessage-database/latest/imessage_database/message_types/expressives/enum.Expressive.html
                    // com.apple.MobileSMS.expressivesend.gentle
                    // com.apple.MobileSMS.expressivesend.impact
                    // com.apple.MobileSMS.expressivesend.invisibleink
                    // com.apple.MobileSMS.expressivesend.loud
                    // com.apple.messages.effect.CKConfettiEffect
                    // com.apple.messages.effect.CKEchoEffect
                    // com.apple.messages.effect.CKFireworksEffect
                    // com.apple.messages.effect.CKHappyBirthdayEffect
                    // com.apple.messages.effect.CKHeartEffect
                    // com.apple.messages.effect.CKLasersEffect
                    // com.apple.messages.effect.CKShootingStarEffect
                    // com.apple.messages.effect.CKSparklesEffect
                    // com.apple.messages.effect.CKSpotlightEffect
                    String normalized =
                        effectName
                            .trim()
                            .toLowerCase(Locale.ROOT)
                            .replace("-", "_")
                            .replace(" ", "_");
                    return switch (normalized) {
                      case "slam", "impact" -> "com.apple.MobileSMS.expressivesend.impact";
                      case "gentle" -> "com.apple.MobileSMS.expressivesend.gentle";
                      case "invisible", "invisible_ink" ->
                          "com.apple.MobileSMS.expressivesend.invisibleink";
                      case "loud" -> "com.apple.MobileSMS.expressivesend.loud";
                      case "confetti" -> "com.apple.messages.effect.CKConfettiEffect";
                      case "echo" -> "com.apple.messages.effect.CKEchoEffect";
                      case "fireworks" -> "com.apple.messages.effect.CKFireworksEffect";
                      case "happy_birthday" -> "com.apple.messages.effect.CKHappyBirthdayEffect";
                      case "heart", "love" -> "com.apple.messages.effect.CKHeartEffect";
                      case "lasers" -> "com.apple.messages.effect.CKLasersEffect";
                      case "shooting_star" -> "com.apple.messages.effect.CKShootingStarEffect";
                      case "sparkles" -> "com.apple.messages.effect.CKSparklesEffect";
                      case "spotlight" -> "com.apple.messages.effect.CKSpotlightEffect";
                      default -> null;
                    };
                  })
              .ifPresent(request::effectId);

          Optional.of(args)
              .map(a -> a.get("partIndex"))
              .filter(Predicate.not(JsonNode::isNull))
              .filter(JsonNode::isNumber)
              .map(JsonNode::asInt)
              .ifPresent(request::partIndex);

          bbHttpClientWrapper.sendTextDirect(request);
          context.recordAssistantTurn(message);
          return "sent";
        });
  }
}
