package io.breland.bbagent.server.agent.persistence.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_feedback")
@Getter
@Setter
@NoArgsConstructor
public class AgentFeedbackEntity {

  @Id
  @Column(name = "feedback_id", nullable = false, length = 36)
  private String feedbackId;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  @Column(name = "feedback_text", nullable = false, columnDefinition = "TEXT")
  private String feedbackText;

  @Column(name = "category", nullable = false, length = 64)
  private String category;

  @Column(name = "transport", nullable = false, length = 64)
  private String transport;

  @Column(name = "sender", length = 512)
  private String sender;

  @Column(name = "chat_guid", length = 255)
  private String chatGuid;

  @Column(name = "message_guid", length = 255)
  private String messageGuid;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public AgentFeedbackEntity(
      String feedbackId,
      String accountId,
      Instant submittedAt,
      String feedbackText,
      String category,
      String transport,
      String sender,
      String chatGuid,
      String messageGuid,
      Instant readAt,
      Instant createdAt,
      Instant updatedAt) {
    this.feedbackId = feedbackId;
    this.accountId = accountId;
    this.submittedAt = submittedAt;
    this.feedbackText = feedbackText;
    this.category = category;
    this.transport = transport;
    this.sender = sender;
    this.chatGuid = chatGuid;
    this.messageGuid = messageGuid;
    this.readAt = readAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
