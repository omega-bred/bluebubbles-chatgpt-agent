package io.breland.bbagent.server.feedback;

import io.breland.bbagent.generated.model.AdminFeedbackItem;
import io.breland.bbagent.generated.model.AdminFeedbackListResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.feedback.AgentFeedbackEntity;
import io.breland.bbagent.server.agent.persistence.feedback.AgentFeedbackRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 250;

  private final AgentFeedbackRepository repository;

  public FeedbackService(AgentFeedbackRepository repository) {
    this.repository = repository;
  }

  @Transactional
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
    AgentFeedbackEntity entity =
        repository.save(
            new AgentFeedbackEntity(
                UUID.randomUUID().toString(),
                resolvedAccountId,
                message != null && message.timestamp() != null ? message.timestamp() : now,
                resolvedFeedback,
                normalizeCategory(category),
                message == null
                    ? IncomingMessage.TRANSPORT_BLUEBUBBLES
                    : message.transportOrDefault(),
                StringUtils.trimToNull(message == null ? null : message.sender()),
                StringUtils.trimToNull(message == null ? null : message.chatGuid()),
                StringUtils.trimToNull(message == null ? null : message.messageGuid()),
                null,
                now,
                now));
    return new RecordedFeedback(entity.getFeedbackId(), entity.getSubmittedAt());
  }

  @Transactional(readOnly = true)
  public AdminFeedbackListResponse listFeedback(String status, Integer limit) {
    FeedbackStatus resolvedStatus = FeedbackStatus.from(status);
    PageRequest page = PageRequest.of(0, resolveLimit(limit));
    List<AgentFeedbackEntity> feedback =
        switch (resolvedStatus) {
          case UNREAD -> repository.findAllByReadAtIsNullOrderBySubmittedAtDesc(page);
          case READ -> repository.findAllByReadAtIsNotNullOrderBySubmittedAtDesc(page);
          case ALL -> repository.findAllByOrderBySubmittedAtDesc(page);
        };
    long unreadCount = repository.countByReadAtIsNull();
    long readCount = repository.countByReadAtIsNotNull();

    return new AdminFeedbackListResponse()
        .status(AdminFeedbackListResponse.StatusEnum.fromValue(resolvedStatus.value))
        .items(feedback.stream().map(this::toItem).toList())
        .unreadCount(unreadCount)
        .readCount(readCount)
        .totalCount(unreadCount + readCount);
  }

  @Transactional
  public Optional<AdminFeedbackItem> markRead(String feedbackId) {
    return repository
        .findById(StringUtils.trimToEmpty(feedbackId))
        .map(
            entity -> {
              Instant now = Instant.now();
              if (entity.getReadAt() == null) {
                entity.setReadAt(now);
              }
              entity.setUpdatedAt(now);
              return toItem(repository.save(entity));
            });
  }

  @Transactional
  public Optional<AdminFeedbackItem> markUnread(String feedbackId) {
    return repository
        .findById(StringUtils.trimToEmpty(feedbackId))
        .map(
            entity -> {
              entity.setReadAt(null);
              entity.setUpdatedAt(Instant.now());
              return toItem(repository.save(entity));
            });
  }

  private AdminFeedbackItem toItem(AgentFeedbackEntity entity) {
    return new AdminFeedbackItem()
        .feedbackId(entity.getFeedbackId())
        .accountId(entity.getAccountId())
        .accountBucket(accountBucket(entity.getAccountId()))
        .submittedAt(offset(entity.getSubmittedAt()))
        .feedbackText(entity.getFeedbackText())
        .category(entity.getCategory())
        .transport(entity.getTransport())
        .sender(entity.getSender())
        .chatGuid(entity.getChatGuid())
        .messageGuid(entity.getMessageGuid())
        .readStatus(
            entity.getReadAt() == null
                ? AdminFeedbackItem.ReadStatusEnum.UNREAD
                : AdminFeedbackItem.ReadStatusEnum.READ)
        .readAt(entity.getReadAt() == null ? null : offset(entity.getReadAt()));
  }

  private OffsetDateTime offset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private int resolveLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
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

  private String accountBucket(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return "unknown";
    }
    return StringUtils.truncate(accountId, 8);
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
