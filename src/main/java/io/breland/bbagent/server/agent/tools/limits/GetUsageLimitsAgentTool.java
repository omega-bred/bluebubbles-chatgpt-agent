package io.breland.bbagent.server.agent.tools.limits;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public class GetUsageLimitsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_usage_limits";

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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record UsageLimitsResponse(
      boolean tracked,
      String accountId,
      @JsonProperty("is_premium") boolean premium,
      String limitKey,
      String limitLabel,
      Long used,
      Long limit,
      Long remaining,
      Double percentage,
      Boolean exhausted,
      String windowStart,
      String windowEnd,
      String userFacingText) {}

  private UsageLimitsResponse toResponse(
      MessageResponseRateLimitService.MessageResponseLimitStatus messageStatus) {
    RateLimitStatus status = messageStatus.rateLimit();
    return new UsageLimitsResponse(
        messageStatus.tracked(),
        messageStatus.accountId(),
        messageStatus.premium(),
        status == null ? null : status.limitKey(),
        status == null ? null : status.limitLabel(),
        status == null ? null : status.used(),
        status == null ? null : status.limit(),
        status == null ? null : status.remaining(),
        status == null ? null : status.percentage(),
        status == null ? null : status.exhausted(),
        status == null ? null : status.windowStart().toString(),
        status == null ? null : status.windowEnd().toString(),
        userFacingText(messageStatus));
  }

  private String userFacingText(
      MessageResponseRateLimitService.MessageResponseLimitStatus messageStatus) {
    if (!messageStatus.tracked() || messageStatus.rateLimit() == null) {
      return "I could not determine your usage limits for this chat identity.";
    }
    RateLimitStatus status = messageStatus.rateLimit();
    String accountType = messageStatus.premium() ? "premium" : "free";
    String resetAt = status.windowEnd().toString();
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
      text +=
          " Premium accounts currently get "
              + MessageResponseRateLimitService.DEFAULT_PREMIUM_MONTHLY_LIMIT_DISPLAY
              + " messages per month.";
    }
    return text;
  }
}
