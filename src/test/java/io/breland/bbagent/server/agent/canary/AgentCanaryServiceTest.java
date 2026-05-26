package io.breland.bbagent.server.agent.canary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AgentCanaryServiceTest {
  @Autowired private AgentCanaryService canaryService;
  @Autowired private AgentProfileService profileService;
  @Autowired private AgentAccountRepository accountRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void marksLxmfAccountWhenOpeningMessageContainsCanaryMarker() {
    accountRepository.deleteAll();
    IncomingMessage message =
        lxmfMessage("aabbcc", AgentCanaryService.DEFAULT_MARKER + " run_id=test-run");

    String accountId =
        profileService.resolveOrCreateAccount(message).orElseThrow().account().getAccountId();

    var account = accountRepository.findById(accountId).orElseThrow();
    assertTrue(account.isCanaryAccount());
    assertEquals(AgentCanaryService.DEFAULT_LABEL, account.getCanaryLabel());
    assertTrue(profileService.isCanaryAccount(message));
  }

  @Test
  void nonLxmfMarkerDoesNotMarkCanaryAccount() {
    accountRepository.deleteAll();
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;chat-canary-looking",
            "msg-canary-looking",
            null,
            AgentCanaryService.DEFAULT_MARKER,
            false,
            "iMessage",
            "+1 (212) 555-0199",
            false,
            Instant.now(),
            List.of(),
            false);

    String accountId =
        profileService.resolveOrCreateAccount(message).orElseThrow().account().getAccountId();

    assertFalse(accountRepository.findById(accountId).orElseThrow().isCanaryAccount());
  }

  @Test
  void cleanupDeletesExpiredCanaryAccountsAndRateLimitUsage() {
    accountRepository.deleteAll();
    IncomingMessage message =
        lxmfMessage("ddeeff", AgentCanaryService.DEFAULT_MARKER + " run_id=cleanup");
    String accountId =
        profileService.resolveOrCreateAccount(message).orElseThrow().account().getAccountId();
    var account = accountRepository.findById(accountId).orElseThrow();
    account.setCanaryLastSeenAt(Instant.now().minus(Duration.ofDays(2)));
    account.setUpdatedAt(account.getCanaryLastSeenAt());
    accountRepository.saveAndFlush(account);
    jdbcTemplate.update(
        """
        insert into app_rate_limit_usage
          (id, limit_key, scope_type, scope_key, window_start, window_end, amount, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        "message_responses_per_month",
        "account",
        accountId,
        Instant.now().minus(Duration.ofHours(1)),
        Instant.now().plus(Duration.ofHours(1)),
        1L,
        Instant.now(),
        Instant.now());

    int deleted = canaryService.cleanupExpiredCanaryAccounts(Duration.ofHours(1));

    assertEquals(1, deleted);
    assertFalse(accountRepository.findById(accountId).isPresent());
    Integer usageCount =
        jdbcTemplate.queryForObject(
            "select count(*) from app_rate_limit_usage where scope_key = ?",
            Integer.class,
            accountId);
    assertEquals(0, usageCount);
  }

  private IncomingMessage lxmfMessage(String sourceHash, String text) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_LXMF,
        IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_LXMF, sourceHash),
        "msg-" + sourceHash + "-" + Instant.now().toEpochMilli(),
        null,
        text,
        false,
        "LXMF",
        sourceHash,
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
