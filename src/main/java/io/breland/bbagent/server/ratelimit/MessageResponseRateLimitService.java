package io.breland.bbagent.server.ratelimit;

import io.breland.bbagent.generated.model.AdminRateLimitUsage;
import io.breland.bbagent.generated.model.AdminRateLimitUsageResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.ratelimit.AppRateLimitUsageEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class MessageResponseRateLimitService {
  public static final String LIMIT_KEY = "message_responses_per_day";
  public static final String LIMIT_LABEL = "Daily assistant responses";
  public static final String SCOPE_TYPE_ACCOUNT = "account";

  private static final int ACCOUNT_BUCKET_PREFIX_LENGTH = 12;

  private final RateLimitService rateLimitService;
  private final ModelAccessService modelAccessService;
  private final long standardDailyLimit;
  private final long premiumDailyLimit;
  private final Clock clock;

  public MessageResponseRateLimitService(
      RateLimitService rateLimitService,
      ModelAccessService modelAccessService,
      @Value("${bbagent.rate-limit.message-responses.standard-daily-limit:200}")
          long standardDailyLimit,
      @Value("${bbagent.rate-limit.message-responses.premium-daily-limit:5000}")
          long premiumDailyLimit,
      @Nullable Clock clock) {
    this.rateLimitService = rateLimitService;
    this.modelAccessService = modelAccessService;
    this.standardDailyLimit = standardDailyLimit;
    this.premiumDailyLimit = premiumDailyLimit;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public MessageResponseLimitStatus statusFor(IncomingMessage message) {
    ModelAccessService.ModelAccess access = modelAccessService.resolve(message);
    return statusFor(access);
  }

  public MessageResponseLimitStatus statusForAccountId(String accountId) {
    return statusFor(modelAccessService.resolve(accountId));
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
    List<AdminRateLimitUsage> usages =
        LIMIT_KEY.equals(requestedLimitKey)
            ? rateLimitService
                .findUsageForWindow(LIMIT_KEY, SCOPE_TYPE_ACCOUNT, currentWindowStart(), maxRows)
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
    Instant windowStart = currentWindowStart();
    return new RateLimitPolicy(
        LIMIT_KEY,
        LIMIT_LABEL,
        SCOPE_TYPE_ACCOUNT,
        access.accountId(),
        access.premium() ? premiumDailyLimit : standardDailyLimit,
        windowStart,
        windowStart.plusSeconds(24 * 60 * 60));
  }

  private RateLimitStatus untrackedStatus(ModelAccessService.ModelAccess access) {
    Instant windowStart = currentWindowStart();
    long limit = access.premium() ? premiumDailyLimit : standardDailyLimit;
    return new RateLimitStatus(
        LIMIT_KEY,
        LIMIT_LABEL,
        SCOPE_TYPE_ACCOUNT,
        null,
        0L,
        limit,
        windowStart,
        windowStart.plusSeconds(24 * 60 * 60));
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

  private Instant currentWindowStart() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String accountBucket(String accountId) {
    String hash = hash(accountId);
    return hash.substring(0, Math.min(hash.length(), ACCOUNT_BUCKET_PREFIX_LENGTH));
  }

  private String hash(String value) {
    return DigestUtils.sha256Hex(StringUtils.defaultString(value));
  }

  public record MessageResponseLimitStatus(
      boolean tracked, String accountId, boolean premium, RateLimitStatus rateLimit) {}
}
