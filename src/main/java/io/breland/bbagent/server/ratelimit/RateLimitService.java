package io.breland.bbagent.server.ratelimit;

import io.breland.bbagent.server.agent.persistence.ratelimit.AppRateLimitUsageEntity;
import io.breland.bbagent.server.agent.persistence.ratelimit.AppRateLimitUsageRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class RateLimitService {
  private final AppRateLimitUsageRepository repository;

  public RateLimitService(AppRateLimitUsageRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public RateLimitStatus check(RateLimitPolicy policy) {
    validatePolicy(policy);
    long used =
        repository
            .findByLimitKeyAndScopeTypeAndScopeKeyAndWindowStart(
                policy.limitKey(), policy.scopeType(), policy.scopeKey(), policy.windowStart())
            .map(AppRateLimitUsageEntity::getAmount)
            .orElse(0L);
    return status(policy, used);
  }

  @Transactional
  public RateLimitDecision tryConsume(RateLimitPolicy policy, long amount) {
    validatePolicy(policy);
    Assert.isTrue(amount > 0, "rate limit amount must be positive");
    AppRateLimitUsageEntity usage =
        repository
            .findForUpdate(
                policy.limitKey(), policy.scopeType(), policy.scopeKey(), policy.windowStart())
            .orElseGet(() -> createUsage(policy));
    long current = usage.getAmount();
    if (current + amount > policy.limit()) {
      return new RateLimitDecision(status(policy, current), false, amount);
    }
    usage.setAmount(current + amount);
    usage.setUpdatedAt(Instant.now());
    repository.save(usage);
    return new RateLimitDecision(status(policy, usage.getAmount()), true, amount);
  }

  @Transactional(readOnly = true)
  public List<AppRateLimitUsageEntity> findUsageForWindow(
      String limitKey, String scopeType, Instant windowStart, int maxRows) {
    int size = Math.clamp(maxRows, 1, 500);
    return repository.findUsageForWindow(limitKey, scopeType, windowStart, PageRequest.of(0, size));
  }

  private AppRateLimitUsageEntity createUsage(RateLimitPolicy policy) {
    Instant now = Instant.now();
    return repository.save(
        new AppRateLimitUsageEntity(
            UUID.randomUUID().toString(),
            policy.limitKey(),
            policy.scopeType(),
            policy.scopeKey(),
            policy.windowStart(),
            policy.windowEnd(),
            0L,
            now,
            now));
  }

  private RateLimitStatus status(RateLimitPolicy policy, long used) {
    return new RateLimitStatus(
        policy.limitKey(),
        policy.limitLabel(),
        policy.scopeType(),
        policy.scopeKey(),
        used,
        policy.limit(),
        policy.windowStart(),
        policy.windowEnd());
  }

  private void validatePolicy(RateLimitPolicy policy) {
    Assert.notNull(policy, "rate limit policy is required");
    Assert.isTrue(StringUtils.isNotBlank(policy.limitKey()), "rate limit key is required");
    Assert.isTrue(StringUtils.isNotBlank(policy.scopeType()), "rate limit scope type is required");
    Assert.isTrue(StringUtils.isNotBlank(policy.scopeKey()), "rate limit scope key is required");
    Assert.isTrue(policy.limit() >= 0, "rate limit must be non-negative");
    Assert.isTrue(
        policy.windowStart() != null && policy.windowEnd() != null,
        "rate limit window is required");
  }
}
