package io.breland.bbagent.server.agent.tools.limits;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class GetUsageLimitsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_usage_limits";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final MessageResponseRateLimitService messageResponseRateLimitService;

  @Schema(description = "Get current app usage limits for the current chat account.")
  public record GetUsageLimitsRequest(
      @Schema(description = "Optional limit key to inspect. Defaults to message responses.")
          @JsonProperty("limit_key")
          String limitKey) {}

  public GetUsageLimitsAgentTool(MessageResponseRateLimitService messageResponseRateLimitService) {
    this.messageResponseRateLimitService = messageResponseRateLimitService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check how close the current chat account is to its usage limits. Use when the user asks"
            + " about quota, limits, monthly messages, remaining messages, or usage.",
        jsonSchema(GetUsageLimitsRequest.class),
        false,
        (context, args) -> {
          try {
            MessageResponseRateLimitService.MessageResponseLimitStatus status =
                messageResponseRateLimitService.statusFor(context.message());
            return ToolJson.stringify(
                context.getMapper(), toResponse(status), "error: unable to encode usage limits");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  private Map<String, Object> toResponse(
      MessageResponseRateLimitService.MessageResponseLimitStatus messageStatus) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("tracked", messageStatus.tracked());
    response.put("account_id", messageStatus.accountId());
    response.put("is_premium", messageStatus.premium());
    RateLimitStatus status = messageStatus.rateLimit();
    if (status != null) {
      response.put("limit_key", status.limitKey());
      response.put("limit_label", status.limitLabel());
      response.put("used", status.used());
      response.put("limit", status.limit());
      response.put("remaining", status.remaining());
      response.put("percentage", status.percentage());
      response.put("exhausted", status.exhausted());
      response.put("window_start", INSTANT_FORMATTER.format(status.windowStart()));
      response.put("window_end", INSTANT_FORMATTER.format(status.windowEnd()));
    }
    response.put("user_facing_text", userFacingText(messageStatus));
    return response;
  }

  private String userFacingText(
      MessageResponseRateLimitService.MessageResponseLimitStatus messageStatus) {
    if (!messageStatus.tracked() || messageStatus.rateLimit() == null) {
      return "I could not determine your usage limits for this chat identity.";
    }
    RateLimitStatus status = messageStatus.rateLimit();
    String accountType = messageStatus.premium() ? "premium" : "free";
    String resetAt = INSTANT_FORMATTER.format(status.windowEnd());
    String text =
        "You have used "
            + status.used()
            + " of "
            + status.limit()
            + " monthly assistant responses on the "
            + accountType
            + " account. "
            + status.remaining()
            + " remain before the limit resets at "
            + resetAt
            + ".";
    if (!messageStatus.premium() && status.remaining() <= 25) {
      text += " Premium accounts currently get 5,000 messages per month.";
    }
    return text;
  }
}
