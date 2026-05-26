package io.breland.bbagent.server.agent.canary;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AgentCanaryService {
  public static final String DEFAULT_MARKER = "BBAGENT_LXMF_CANARY_V1";
  public static final String DEFAULT_LABEL = "lxmf-free-tier";

  private final AgentAccountRepository accountRepository;
  private final AgentAccountResolver accountResolver;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;
  private final String marker;
  private final String label;
  private final Duration cleanupMaxAge;
  private final boolean cleanupEnabled;

  public AgentCanaryService(
      AgentAccountRepository accountRepository,
      AgentAccountResolver accountResolver,
      JdbcTemplate jdbcTemplate,
      @Value("${bbagent.canary.marker:" + DEFAULT_MARKER + "}") String marker,
      @Value("${bbagent.canary.label:" + DEFAULT_LABEL + "}") String label,
      @Value("${bbagent.canary.cleanup-max-age:PT24H}") Duration cleanupMaxAge,
      @Value("${bbagent.canary.cleanup-enabled:true}") boolean cleanupEnabled,
      @Nullable Clock clock) {
    this.accountRepository = accountRepository;
    this.accountResolver = accountResolver;
    this.jdbcTemplate = jdbcTemplate;
    this.marker = StringUtils.defaultIfBlank(marker, DEFAULT_MARKER);
    this.label = StringUtils.defaultIfBlank(label, DEFAULT_LABEL);
    this.cleanupMaxAge = cleanupMaxAge == null ? Duration.ofHours(24) : cleanupMaxAge;
    this.cleanupEnabled = cleanupEnabled;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public Optional<AgentAccountResolver.ResolvedAccount> touchIfCanary(
      IncomingMessage message, Optional<AgentAccountResolver.ResolvedAccount> resolvedAccount) {
    if (message == null || resolvedAccount == null || resolvedAccount.isEmpty()) {
      return resolvedAccount == null ? Optional.empty() : resolvedAccount;
    }
    AgentAccountEntity account = resolvedAccount.get().account();
    if (!isCanaryOpeningMessage(message) && !account.isCanaryAccount()) {
      return resolvedAccount;
    }
    markOrTouch(account, isCanaryOpeningMessage(message));
    return accountResolver.resolveById(account.getAccountId());
  }

  @Transactional(readOnly = true)
  public boolean isCanaryAccount(IncomingMessage message) {
    if (message == null) {
      return false;
    }
    return accountResolver
        .resolve(message)
        .map(AgentAccountResolver.ResolvedAccount::account)
        .map(AgentAccountEntity::isCanaryAccount)
        .orElse(false);
  }

  public boolean isCanaryOpeningMessage(IncomingMessage message) {
    if (message == null || !message.isLxmfTransport() || StringUtils.isBlank(message.text())) {
      return false;
    }
    return StringUtils.trimToEmpty(message.text()).startsWith(marker);
  }

  @Scheduled(
      fixedDelayString = "${bbagent.canary.cleanup-interval:PT6H}",
      initialDelayString = "${bbagent.canary.cleanup-initial-delay:PT10M}")
  public void cleanupExpiredCanaryAccounts() {
    if (!cleanupEnabled) {
      return;
    }
    int deleted = cleanupExpiredCanaryAccounts(cleanupMaxAge);
    if (deleted > 0) {
      log.info("Deleted {} expired canary agent account(s)", deleted);
    }
  }

  @Transactional
  public int cleanupExpiredCanaryAccounts(Duration maxAge) {
    Duration effectiveMaxAge = maxAge == null ? cleanupMaxAge : maxAge;
    Instant cutoff = clock.instant().minus(effectiveMaxAge);
    List<AgentAccountEntity> expired = accountRepository.findExpiredCanaryAccounts(cutoff);
    if (expired.isEmpty()) {
      return 0;
    }
    List<String> accountIds = expired.stream().map(AgentAccountEntity::getAccountId).toList();
    for (String accountId : accountIds) {
      jdbcTemplate.update(
          "delete from app_rate_limit_usage where scope_type = ? and scope_key = ?",
          "account",
          accountId);
    }
    accountRepository.deleteAll(expired);
    return expired.size();
  }

  private void markOrTouch(AgentAccountEntity account, boolean openingMessage) {
    if (account == null || StringUtils.isBlank(account.getAccountId())) {
      return;
    }
    Instant now = clock.instant();
    if (openingMessage && !account.isCanaryAccount()) {
      log.info("Marking agent account {} as canary account", account.getAccountId());
    }
    account.setCanaryAccount(true);
    account.setCanaryLabel(label);
    account.setCanaryLastSeenAt(now);
    account.setUpdatedAt(now);
    accountRepository.save(account);
  }
}
