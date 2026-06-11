package io.breland.bbagent.server.ratelimit;

import static io.breland.bbagent.server.TimeSupport.offset;

import io.breland.bbagent.generated.model.AdminRateLimitUsage;
import io.breland.bbagent.generated.model.AdminRateLimitUsageResponse;
import io.breland.bbagent.generated.model.WebsiteUsageLimitSummary;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.ratelimit.AppRateLimitUsageEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class MessageResponseRateLimitService {
  public static final String LIMIT_KEY = "message_responses_per_month";
  public static final String LIMIT_LABEL = "Monthly assistant responses";
  public static final String SCOPE_TYPE_ACCOUNT = "account";
  public static final long DEFAULT_PREMIUM_MONTHLY_LIMIT = 5_000L;
  public static final String DEFAULT_PREMIUM_MONTHLY_LIMIT_DISPLAY = "5,000";

  private static final int ACCOUNT_BUCKET_PREFIX_LENGTH = 12;

  private final RateLimitService rateLimitService;
  private final ModelAccessService modelAccessService;
  private final long standardMonthlyLimit;
  private final long premiumMonthlyLimit;
  private final Clock clock;

  public MessageResponseRateLimitService(
      RateLimitService rateLimitService,
      ModelAccessService modelAccessService,
      @Value("${bbagent.rate-limit.message-responses.standard-monthly-limit:200}")
          long standardMonthlyLimit,
      @Value(
              "${bbagent.rate-limit.message-responses.premium-monthly-limit:"
                  + DEFAULT_PREMIUM_MONTHLY_LIMIT
                  + "}")
          long premiumMonthlyLimit,
      @Nullable Clock clock) {
    this.rateLimitService = rateLimitService;
    this.modelAccessService = modelAccessService;
    this.standardMonthlyLimit = standardMonthlyLimit;
    this.premiumMonthlyLimit = premiumMonthlyLimit;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public MessageResponseLimitStatus statusFor(IncomingMessage message) {
    ModelAccessService.ModelAccess access = modelAccessService.resolve(message);
    return statusFor(access);
  }

  public MessageResponseLimitStatus statusForAccountId(String accountId) {
    return statusFor(modelAccessService.resolve(accountId));
  }

  public WebsiteUsageLimitSummary websiteUsageForAccountId(String accountId) {
    MessageResponseLimitStatus status = statusForAccountId(accountId);
    RateLimitStatus rateLimit = status.rateLimit();
    if (rateLimit == null) {
      rateLimit = untrackedStatus(modelAccessService.resolve(accountId));
    }
    return new WebsiteUsageLimitSummary()
        .limitKey(rateLimit.limitKey())
        .limitLabel(rateLimit.limitLabel())
        .used(rateLimit.used())
        .limit(rateLimit.limit())
        .remaining(rateLimit.remaining())
        .percentage(rateLimit.percentage())
        .exhausted(rateLimit.exhausted())
        .windowStart(offset(rateLimit.windowStart()))
        .windowEnd(offset(rateLimit.windowEnd()));
  }

  public RateLimitDecision tryConsume(IncomingMessage message) {
    ModelAccessService.ModelAccess access = modelAccessService.resolve(message);
    if (StringUtils.isBlank(access.accountId())) {
      return new RateLimitDecision(untrackedStatus(access), true, 1L);
    }
    return rateLimitService.tryConsume(policyFor(access), 1L);
  }

  public AdminRateLimitUsageResponse adminUsage(@Nullable String limitKey, int maxRows) {
    Instant now = clock.instant();
    String requestedLimitKey = StringUtils.defaultIfBlank(limitKey, LIMIT_KEY);
    RateLimitWindow window = currentWindow();
    List<AdminRateLimitUsage> usages =
        LIMIT_KEY.equals(requestedLimitKey)
            ? rateLimitService
                .findUsageForWindow(LIMIT_KEY, SCOPE_TYPE_ACCOUNT, window.start(), maxRows)
                .stream()
                .map(this::toAdminUsage)
                .toList()
            : List.of();
    return new AdminRateLimitUsageResponse()
        .generatedAt(offset(now))
        .limitKey(requestedLimitKey)
        .usages(usages);
  }

  private MessageResponseLimitStatus statusFor(ModelAccessService.ModelAccess access) {
    if (StringUtils.isBlank(access.accountId())) {
      return new MessageResponseLimitStatus(false, null, access.premium(), null);
    }
    return new MessageResponseLimitStatus(
        true, access.accountId(), access.premium(), rateLimitService.check(policyFor(access)));
  }

  private RateLimitPolicy policyFor(ModelAccessService.ModelAccess access) {
    RateLimitWindow window = currentWindow();
    return new RateLimitPolicy(
        LIMIT_KEY,
        LIMIT_LABEL,
        SCOPE_TYPE_ACCOUNT,
        access.accountId(),
        access.premium() ? premiumMonthlyLimit : standardMonthlyLimit,
        window.start(),
        window.end());
  }

  private RateLimitStatus untrackedStatus(ModelAccessService.ModelAccess access) {
    RateLimitWindow window = currentWindow();
    long limit = access.premium() ? premiumMonthlyLimit : standardMonthlyLimit;
    return new RateLimitStatus(
        LIMIT_KEY, LIMIT_LABEL, SCOPE_TYPE_ACCOUNT, null, 0L, limit, window.start(), window.end());
  }

  private AdminRateLimitUsage toAdminUsage(AppRateLimitUsageEntity usage) {
    MessageResponseLimitStatus status = statusForAccountId(usage.getScopeKey());
    RateLimitStatus limitStatus = status.rateLimit();
    long limit = limitStatus == null ? 0L : limitStatus.limit();
    long remaining = limitStatus == null ? 0L : limitStatus.remaining();
    double percentage = limitStatus == null ? 0.0 : limitStatus.percentage();
    return new AdminRateLimitUsage()
        .limitKey(usage.getLimitKey())
        .limitLabel(LIMIT_LABEL)
        .scopeType(usage.getScopeType())
        .scopeKey(usage.getScopeKey())
        .accountId(usage.getScopeKey())
        .accountBucket(accountBucket(usage.getScopeKey()))
        .isPremium(status.premium())
        .used(usage.getAmount())
        .limit(limit)
        .remaining(remaining)
        .percentage(percentage)
        .exhausted(limitStatus != null && limitStatus.exhausted())
        .windowStart(offset(usage.getWindowStart()))
        .windowEnd(offset(usage.getWindowEnd()))
        .updatedAt(offset(usage.getUpdatedAt()));
  }

  private RateLimitWindow currentWindow() {
    YearMonth currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    Instant start = currentMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = currentMonth.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return new RateLimitWindow(start, end);
  }

  private String accountBucket(String accountId) {
    String hash = hash(accountId);
    return StringUtils.truncate(hash, ACCOUNT_BUCKET_PREFIX_LENGTH);
  }

  private String hash(String value) {
    return DigestUtils.sha256Hex(StringUtils.defaultString(value));
  }

  public record MessageResponseLimitStatus(
      boolean tracked, String accountId, boolean premium, RateLimitStatus rateLimit) {}

  private record RateLimitWindow(Instant start, Instant end) {}
}
