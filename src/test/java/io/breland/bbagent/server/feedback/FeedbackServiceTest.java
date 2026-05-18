package io.breland.bbagent.server.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.feedback.AgentFeedbackRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class FeedbackServiceTest {
  @Autowired private FeedbackService feedbackService;
  @Autowired private AgentFeedbackRepository feedbackRepository;
  @Autowired private AgentAccountResolver accountResolver;

  @Test
  void recordsFeedbackAndTogglesReadState() {
    feedbackRepository.deleteAll();
    String accountId =
        accountResolver
            .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, "Alice")
            .orElseThrow()
            .account()
            .getAccountId();

    FeedbackService.RecordedFeedback recorded =
        feedbackService.recordFeedback(
            incomingMessage(),
            accountId,
            "tell your creator the model needs better tool hints",
            "tool");

    assertNotNull(recorded.feedbackId());
    AdminFeedbackListResponse unread = feedbackService.listFeedback("unread", 100);
    assertEquals(1L, unread.getUnreadCount());
    assertEquals(1, unread.getItems().size());
    assertEquals("tool", unread.getItems().get(0).getCategory());
    assertEquals(
        "tell your creator the model needs better tool hints",
        unread.getItems().get(0).getFeedbackText());

    assertTrue(feedbackService.markRead(recorded.feedbackId()).isPresent());
    assertEquals(0, feedbackService.listFeedback("unread", 100).getItems().size());
    assertEquals(1, feedbackService.listFeedback("read", 100).getItems().size());

    assertTrue(feedbackService.markUnread(recorded.feedbackId()).isPresent());
    assertEquals(1, feedbackService.listFeedback("unread", 100).getItems().size());
  }

  private IncomingMessage incomingMessage() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "tell your creator the model needs better tool hints",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.parse("2026-05-01T00:00:00Z"),
        List.of(),
        false);
  }
}
