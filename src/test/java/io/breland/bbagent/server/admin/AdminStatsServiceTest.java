package io.breland.bbagent.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AdminStatsServiceTest {
  @Autowired private AdminStatsService adminStatsService;
  @Autowired private AgentMessageMetricRepository metricRepository;
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private AgentAccountRepository accountRepository;

  @Test
  void recordsAcceptedMessagesAndSummarizesByModelAndUser() {
    metricRepository.deleteAll();
    Instant now = Instant.now();
    var alice =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account();
    alice.setPremium(true);
    accountRepository.save(alice);

    adminStatsService.recordAcceptedMessage(
        incomingMessage("chat-1", "msg-1", "Alice"), AgentWorkflowProperties.Mode.INLINE);
    adminStatsService.recordAcceptedMessage(
        incomingMessage("chat-2", "msg-2", "Bob"), AgentWorkflowProperties.Mode.CADENCE);

    AdminStatsResponse stats =
        adminStatsService.getStatistics(now.minusSeconds(60), now.plusSeconds(60));

    assertEquals(2L, stats.getTotalMessages());
    assertEquals(2L, stats.getActiveUsers());
    assertTrue(
        stats.getModels().stream()
            .anyMatch(
                model -> ModelAccessService.PREMIUM_MODEL_LABEL.equals(model.getModelLabel())));
    assertTrue(
        stats.getModels().stream()
            .anyMatch(
                model -> ModelAccessService.STANDARD_MODEL_LABEL.equals(model.getModelLabel())));
    assertEquals(
        2L, stats.getTimeline().stream().mapToLong(bucket -> bucket.getMessageCount()).sum());
  }

  private IncomingMessage incomingMessage(String chatGuid, String messageGuid, String sender) {
    return new IncomingMessage(
        chatGuid,
        messageGuid,
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
