package io.breland.bbagent.server.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.generated.model.AdminFeedbackListResponse.StatusEnum;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.FeedbackIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

  @ParameterizedTest
  @MethodSource("feedbackStatuses")
  void legacyAdminFeedbackInboxReturnsEmptyAfterPostgresCleanup(
      String status, StatusEnum expectedStatus) {
    FeedbackService feedbackService = new FeedbackService(mock(LinearIssueService.class));

    AdminFeedbackListResponse response = feedbackService.listFeedback(status, 100);

    assertEquals(expectedStatus, response.getStatus());
    assertTrue(response.getItems().isEmpty());
    assertEquals(0L, response.getUnreadCount());
    assertEquals(0L, response.getReadCount());
    assertEquals(0L, response.getTotalCount());
    assertTrue(feedbackService.markRead("BLU-456").isEmpty());
    assertTrue(feedbackService.markUnread("BLU-456").isEmpty());
  }

  private static Stream<Arguments> feedbackStatuses() {
    return Stream.of(
        Arguments.of(null, StatusEnum.UNREAD),
        Arguments.of("", StatusEnum.UNREAD),
        Arguments.of(" \t ", StatusEnum.UNREAD),
        Arguments.of("all", StatusEnum.ALL),
        Arguments.of("ALL", StatusEnum.ALL),
        Arguments.of(" All ", StatusEnum.ALL),
        Arguments.of("read", StatusEnum.READ),
        Arguments.of("READ", StatusEnum.READ),
        Arguments.of("\tReAd\n", StatusEnum.READ),
        Arguments.of("unread", StatusEnum.UNREAD),
        Arguments.of("UNREAD", StatusEnum.UNREAD),
        Arguments.of("unsupported", StatusEnum.UNREAD));
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
