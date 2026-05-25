package io.breland.bbagent.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricRepository;
import io.breland.bbagent.server.agent.persistence.metrics.AgentToolMetricRepository;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
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
  @Autowired private AgentToolMetricRepository toolMetricRepository;
  @Autowired private AgentAccountResolver accountResolver;
  @Autowired private AgentAccountRepository accountRepository;

  @Test
  void recordsAcceptedMessagesAndSummarizesByModelAndUser() {
    metricRepository.deleteAll();
    toolMetricRepository.deleteAll();
    Instant now = Instant.now();
    var alice =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account();
    alice.setPremium(true);
    accountRepository.save(alice);

    adminStatsService.recordAcceptedMessage(incomingMessage("chat-1", "msg-1", "Alice"));
    adminStatsService.recordAcceptedMessage(incomingMessage("chat-1", "msg-2", "Alice"));
    adminStatsService.recordAcceptedMessage(incomingMessage("chat-2", "msg-3", "Bob"));

    AdminStatsResponse stats =
        adminStatsService.getStatistics(now.minusSeconds(60), now.plusSeconds(60));

    assertEquals(3L, stats.getTotalMessages());
    assertEquals(2L, stats.getActiveUsers());
    assertEquals(2, stats.getSenders().size());
    assertEquals(2L, stats.getSenders().get(0).getMessageCount());
    assertEquals(2.0 / 3.0, stats.getSenders().get(0).getPercentage(), 0.0001);
    assertEquals(12, stats.getSenders().get(0).getAccountBucket().length());
    assertFalse(stats.getSenders().get(0).getModels().isEmpty());
    assertTrue(
        stats.getModels().stream()
            .anyMatch(
                model -> ModelAccessService.PREMIUM_MODEL_LABEL.equals(model.getModelLabel())));
    assertTrue(
        stats.getModels().stream()
            .anyMatch(
                model -> ModelAccessService.STANDARD_MODEL_LABEL.equals(model.getModelLabel())));
    assertEquals(
        3L, stats.getTimeline().stream().mapToLong(bucket -> bucket.getMessageCount()).sum());
  }

  @Test
  void limitsSenderStatsToTopTenByVolume() {
    metricRepository.deleteAll();
    toolMetricRepository.deleteAll();
    Instant now = Instant.now();

    for (int senderIndex = 0; senderIndex < 12; senderIndex++) {
      int messageCount = senderIndex == 11 ? 3 : 1;
      for (int messageIndex = 0; messageIndex < messageCount; messageIndex++) {
        adminStatsService.recordAcceptedMessage(
            incomingMessage(
                "chat-" + senderIndex,
                "msg-" + senderIndex + "-" + messageIndex,
                "Sender " + senderIndex));
      }
    }

    AdminStatsResponse stats =
        adminStatsService.getStatistics(now.minusSeconds(60), now.plusSeconds(60));

    assertEquals(10, stats.getSenders().size());
    assertEquals(3L, stats.getSenders().get(0).getMessageCount());
  }

  @Test
  void recordsToolCallsAndSummarizesByToolAndAccountType() {
    metricRepository.deleteAll();
    toolMetricRepository.deleteAll();
    Instant now = Instant.now();
    var alice =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account();
    alice.setPremium(true);
    accountRepository.save(alice);

    adminStatsService.recordToolCall(
        new AgentToolMetricEvent(
            incomingMessage("chat-1", "msg-1", "Alice"),
            "send_text",
            "bluebubbles",
            true,
            null,
            25L));
    adminStatsService.recordToolCall(
        new AgentToolMetricEvent(
            incomingMessage("chat-1", "msg-2", "Alice"),
            "list_calendar_events",
            "google_calendar",
            false,
            "tool_error",
            50L));
    adminStatsService.recordToolCall(
        new AgentToolMetricEvent(
            incomingMessage("chat-2", "msg-3", "Bob"),
            "send_text",
            "bluebubbles",
            true,
            null,
            75L));

    AdminStatsResponse stats =
        adminStatsService.getStatistics(now.minusSeconds(60), now.plusSeconds(60));

    assertEquals(3L, stats.getTotalToolCalls());
    assertEquals(2L, stats.getSuccessfulToolCalls());
    assertEquals(1L, stats.getFailedToolCalls());
    assertEquals(2.0 / 3.0, stats.getToolSuccessRate(), 0.0001);
    assertEquals("send_text", stats.getTools().get(0).getToolName());
    assertEquals(2L, stats.getTools().get(0).getCallCount());
    assertEquals(1.0, stats.getTools().get(0).getSuccessRate(), 0.0001);
    assertTrue(
        stats.getToolAccountTypes().stream()
            .anyMatch(
                accountType ->
                    Boolean.TRUE.equals(accountType.getIsPremium())
                        && accountType.getCallCount() == 2L
                        && accountType.getFailedCalls() == 1L));
    assertTrue(
        stats.getToolAccountTypes().stream()
            .anyMatch(
                accountType ->
                    !Boolean.TRUE.equals(accountType.getIsPremium())
                        && accountType.getCallCount() == 1L
                        && accountType.getSuccessfulCalls() == 1L));
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
