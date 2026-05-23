package io.breland.bbagent.server.feedback;

import io.breland.bbagent.generated.model.AdminFeedbackItem;
import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.FeedbackIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
  private final LinearIssueService linearIssueService;

  public FeedbackService(LinearIssueService linearIssueService) {
    this.linearIssueService = linearIssueService;
  }

  public RecordedFeedback recordFeedback(
      IncomingMessage message, String accountId, String feedbackText, String category) {
    String resolvedAccountId = StringUtils.trimToNull(accountId);
    if (resolvedAccountId == null) {
      throw new IllegalArgumentException("missing account id");
    }
    String resolvedFeedback = StringUtils.trimToNull(feedbackText);
    if (resolvedFeedback == null) {
      throw new IllegalArgumentException("missing feedback");
    }

    Instant now = Instant.now();
    Instant submittedAt =
        message != null && message.timestamp() != null ? message.timestamp() : now;
    LinearIssue issue =
        linearIssueService.createFeedbackIssue(
            new FeedbackIssueInput(
                resolvedAccountId,
                submittedAt,
                resolvedFeedback,
                normalizeCategory(category),
                message == null
                    ? IncomingMessage.TRANSPORT_BLUEBUBBLES
                    : message.transportOrDefault(),
                StringUtils.trimToNull(message == null ? null : message.sender()),
                StringUtils.trimToNull(message == null ? null : message.chatGuid()),
                StringUtils.trimToNull(message == null ? null : message.messageGuid())));
    return new RecordedFeedback(issue.reference(), submittedAt);
  }

  public AdminFeedbackListResponse listFeedback(String status, Integer limit) {
    FeedbackStatus resolvedStatus = FeedbackStatus.from(status);
    return new AdminFeedbackListResponse()
        .status(AdminFeedbackListResponse.StatusEnum.fromValue(resolvedStatus.value))
        .items(List.of())
        .unreadCount(0L)
        .readCount(0L)
        .totalCount(0L);
  }

  public Optional<AdminFeedbackItem> markRead(String feedbackId) {
    return Optional.empty();
  }

  public Optional<AdminFeedbackItem> markUnread(String feedbackId) {
    return Optional.empty();
  }

  private String normalizeCategory(String category) {
    String normalized = StringUtils.trimToNull(category);
    if (normalized == null) {
      return "general";
    }
    normalized = normalized.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    normalized = StringUtils.truncate(normalized, 64);
    return StringUtils.defaultIfBlank(normalized, "general");
  }

  public record RecordedFeedback(String feedbackId, Instant submittedAt) {}

  private enum FeedbackStatus {
    ALL("all"),
    UNREAD("unread"),
    READ("read");

    private final String value;

    FeedbackStatus(String value) {
      this.value = value;
    }

    private static FeedbackStatus from(String value) {
      if (value == null || value.isBlank()) {
        return UNREAD;
      }
      for (FeedbackStatus status : values()) {
        if (status.value.equalsIgnoreCase(value.trim())) {
          return status;
        }
      }
      return UNREAD;
    }
  }
}
