package io.breland.bbagent.server.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.FeedbackIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeedbackServiceTest {

  @Test
  void recordsFeedbackAsLinearIssue() {
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    when(linearIssueService.createFeedbackIssue(any(FeedbackIssueInput.class)))
        .thenReturn(
            new LinearIssue(
                "issue-id",
                "BLU-456",
                "[Feedback/tool] model needs better tool hints",
                "https://linear.app/bluechat/issue/BLU-456/model-needs-better-tool-hints",
                Instant.parse("2026-05-01T00:00:00Z")));
    FeedbackService feedbackService = new FeedbackService(linearIssueService);

    FeedbackService.RecordedFeedback recorded =
        feedbackService.recordFeedback(
            incomingMessage(),
            "account-1",
            "tell your creator the model needs better tool hints",
            "tool");

    assertEquals("BLU-456", recorded.feedbackId());
    assertNotNull(recorded.submittedAt());
    ArgumentCaptor<FeedbackIssueInput> issueCaptor =
        ArgumentCaptor.forClass(FeedbackIssueInput.class);
    verify(linearIssueService).createFeedbackIssue(issueCaptor.capture());
    FeedbackIssueInput issue = issueCaptor.getValue();
    assertEquals("account-1", issue.accountId());
    assertEquals("tool", issue.category());
    assertEquals("tell your creator the model needs better tool hints", issue.feedbackText());
    assertEquals("Alice", issue.sender());
  }

  @Test
  void legacyAdminFeedbackInboxReturnsEmptyAfterPostgresCleanup() {
    FeedbackService feedbackService = new FeedbackService(mock(LinearIssueService.class));

    AdminFeedbackListResponse unread = feedbackService.listFeedback("unread", 100);

    assertEquals(AdminFeedbackListResponse.StatusEnum.UNREAD, unread.getStatus());
    assertTrue(unread.getItems().isEmpty());
    assertEquals(0L, unread.getUnreadCount());
    assertEquals(0L, unread.getReadCount());
    assertEquals(0L, unread.getTotalCount());
    assertTrue(feedbackService.markRead("BLU-456").isEmpty());
    assertTrue(feedbackService.markUnread("BLU-456").isEmpty());
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
