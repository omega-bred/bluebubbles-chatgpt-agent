package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import java.util.Optional;

public class SendTextAgentTool implements ToolProvider {

  public static final String TOOL_NAME = "send_text";

  @Schema(description = "Send a text message via the current chat transport.")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SendTextRequest(
      @Schema(description = "Text message to send.", requiredMode = Schema.RequiredMode.REQUIRED)
          String message,
      @Schema(
              description =
                  "Use to reply directly to a message in a thread. Pass the message GUID to reply to.")
          String selectedMessageGuid,
      @Schema(
              description = "Optional iMessage effect to use sparingly.",
              allowableValues = {
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
                "spotlight"
              })
          Effect effect,
      @Schema(description = "Attachment part index when replying with multiple parts.")
          Integer partIndex) {}

  public enum Effect {
    @JsonProperty("slam")
    SLAM,
    @JsonProperty("gentle")
    GENTLE,
    @JsonProperty("invisible")
    INVISIBLE,
    @JsonProperty("loud")
    LOUD,
    @JsonProperty("confetti")
    CONFETTI,
    @JsonProperty("echo")
    ECHO,
    @JsonProperty("fireworks")
    FIREWORKS,
    @JsonProperty("happy_birthday")
    HAPPY_BIRTHDAY,
    @JsonProperty("heart")
    HEART,
    @JsonProperty("love")
    LOVE,
    @JsonProperty("lasers")
    LASERS,
    @JsonProperty("shooting_star")
    SHOOTING_STAR,
    @JsonProperty("sparkles")
    SPARKLES,
    @JsonProperty("spotlight")
    SPOTLIGHT
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Send a plain-text reply via the current chat transport (no markdown or formatting markers). You may optionally apply an iMessage effect when the current transport supports it.",
        jsonSchema(SendTextRequest.class),
        false,
        (context, args) -> {
          SendTextRequest toolRequest =
              context.getMapper().convertValue(args, SendTextRequest.class);
          String message = toolRequest.message();
          if (message == null || message.isBlank()) {
            return "missing message";
          }
          if (!context.canSendResponses()) {
            return "skipped: outdated workflow";
          }
          String effectId =
              Optional.ofNullable(toolRequest.effect()).map(this::effectId).orElse(null);
          context.sendText(
              new OutgoingTextMessage(
                  message, toolRequest.selectedMessageGuid(), effectId, toolRequest.partIndex()));
          context.recordAssistantTurn(message);
          return "sent";
        });
  }

  private String effectId(Effect effect) {
    if (effect == null) {
      return null;
    }
    // https://docs.rs/imessage-database/latest/imessage_database/message_types/expressives/enum.Expressive.html
    String normalized =
        effect.name().trim().toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    return switch (normalized) {
      case "slam", "impact" -> "com.apple.MobileSMS.expressivesend.impact";
      case "gentle" -> "com.apple.MobileSMS.expressivesend.gentle";
      case "invisible", "invisible_ink" -> "com.apple.MobileSMS.expressivesend.invisibleink";
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
  }
}
