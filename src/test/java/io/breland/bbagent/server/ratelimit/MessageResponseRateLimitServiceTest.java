package io.breland.bbagent.server.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.model.AdminRateLimitUsage;
import io.breland.bbagent.generated.model.AdminRateLimitUsageResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.ratelimit.AppRateLimitUsageRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MessageResponseRateLimitServiceTest {
  @Autowired private MessageResponseRateLimitService service;
  @Autowired private AppRateLimitUsageRepository usageRepository;
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private AgentAccountRepository accountRepository;

  @Test
  void enforcesDailyStandardAccountLimit() {
    usageRepository.deleteAll();
    IncomingMessage message = incomingMessage("Alice");

    MessageResponseRateLimitService.MessageResponseLimitStatus initial = service.statusFor(message);
    assertTrue(initial.tracked());
    assertEquals(200L, initial.rateLimit().limit());
    assertEquals(200L, initial.rateLimit().remaining());

    for (int i = 0; i < 200; i++) {
      assertTrue(service.tryConsume(message).allowed());
    }

    RateLimitDecision denied = service.tryConsume(message);
    assertFalse(denied.allowed());
    assertEquals(200L, denied.status().used());
    assertEquals(0L, denied.status().remaining());
  }

  @Test
  void premiumAccountsUsePaidDailyLimit() {
    usageRepository.deleteAll();
    var account =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account();
    account.setPremium(true);
    accountRepository.save(account);

    MessageResponseRateLimitService.MessageResponseLimitStatus status =
        service.statusFor(incomingMessage("Alice"));

    assertTrue(status.premium());
    assertEquals(5000L, status.rateLimit().limit());
  }

  @Test
  void upgradedAccountsUsePaidLimitForExistingDailyUsageAndAdminMetering() {
    usageRepository.deleteAll();
    IncomingMessage message = incomingMessage("Alice");

    for (int i = 0; i < 200; i++) {
      assertTrue(service.tryConsume(message).allowed());
    }
    assertFalse(service.tryConsume(message).allowed());

    var account =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account();
    account.setPremium(true);
    accountRepository.saveAndFlush(account);

    MessageResponseRateLimitService.MessageResponseLimitStatus status = service.statusFor(message);

    assertTrue(status.premium());
    assertEquals(5000L, status.rateLimit().limit());
    assertEquals(200L, status.rateLimit().used());
    assertEquals(4800L, status.rateLimit().remaining());

    assertTrue(service.tryConsume(message).allowed());

    AdminRateLimitUsage usage = service.adminUsage(null, 10).getUsages().get(0);
    assertEquals(account.getAccountId(), usage.getAccountId());
    assertTrue(usage.getIsPremium());
    assertEquals(201L, usage.getUsed());
    assertEquals(5000L, usage.getLimit());
    assertEquals(4799L, usage.getRemaining());
  }

  @Test
  void adminUsageListsCurrentWindowUsage() {
    usageRepository.deleteAll();
    IncomingMessage message = incomingMessage("Alice");
    assertTrue(service.tryConsume(message).allowed());
    assertTrue(service.tryConsume(message).allowed());

    AdminRateLimitUsageResponse response = service.adminUsage(null, 10);

    assertEquals(MessageResponseRateLimitService.LIMIT_KEY, response.getLimitKey());
    assertEquals(1, response.getUsages().size());
    assertEquals(2L, response.getUsages().get(0).getUsed());
    assertEquals(200L, response.getUsages().get(0).getLimit());
  }

  private IncomingMessage incomingMessage(String sender) {
    return new IncomingMessage(
        "iMessage;+;chat-" + sender,
        "msg-" + sender + "-" + Instant.now().toEpochMilli(),
        null,
        "hello",
        false,
        "iMessage",
        sender,
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
