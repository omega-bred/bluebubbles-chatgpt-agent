package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitStatus;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

@Slf4j
final class MessageResponseLimitNoticeFactory {
  private final @Nullable WebsiteAccountService websiteAccountService;

  MessageResponseLimitNoticeFactory(@Nullable WebsiteAccountService websiteAccountService) {
    this.websiteAccountService = websiteAccountService;
  }

  String rateLimitExceededText(
      IncomingMessage message, MessageResponseRateLimitService.MessageResponseLimitStatus status) {
    RateLimitStatus rateLimit = status.rateLimit();
    String resetAt = rateLimit == null ? "the next UTC month" : rateLimit.windowEnd().toString();
    long limit = rateLimit == null ? 0L : rateLimit.limit();
    if (!status.premium()) {
      StringBuilder text =
          new StringBuilder(
              "You've hit the free monthly limit of "
                  + limit
                  + " messages. Premium accounts currently get "
                  + MessageResponseRateLimitService.DEFAULT_PREMIUM_MONTHLY_LIMIT_DISPLAY
                  + " messages per month. ");
      createUpgradeLinkText(message)
          .ifPresentOrElse(
              text::append,
              () ->
                  text.append(
                      "Link this chat identity to the website and upgrade to keep chatting this month. "));
      text.append("Your free limit resets at ").append(resetAt).append(".");
      return text.toString();
    }
    return "You've hit the premium monthly limit of "
        + limit
        + " messages. Your limit resets at "
        + resetAt
        + ".";
  }

  private Optional<String> createUpgradeLinkText(IncomingMessage message) {
    if (websiteAccountService == null || message == null) {
      return Optional.empty();
    }
    try {
      WebsiteAccountService.CreatedLinkToken link = websiteAccountService.createLinkToken(message);
      return Optional.of(
          "Open this link to log in or sign up, connect this chat identity, and upgrade: "
              + link.url()
              + " ");
    } catch (RuntimeException e) {
      log.warn("Failed to create upgrade account link for rate limit notice", e);
      return Optional.empty();
    }
  }
}
