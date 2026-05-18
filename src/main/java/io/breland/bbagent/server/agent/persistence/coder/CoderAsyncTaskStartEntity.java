package io.breland.bbagent.server.agent.persistence.coder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coder_async_task_starts")
@Getter
@Setter
@NoArgsConstructor
public class CoderAsyncTaskStartEntity {
  @Id
  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "chat_guid", nullable = false, length = 255)
  private String chatGuid;

  @Column(name = "message_guid", length = 255)
  private String messageGuid;

  @Column(name = "task_hash", nullable = false, length = 128)
  private String taskHash;

  @Column(name = "task", nullable = false, columnDefinition = "TEXT")
  private String task;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "response_json", columnDefinition = "TEXT")
  private String responseJson;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public CoderAsyncTaskStartEntity(
      String idempotencyKey,
      String accountId,
      String chatGuid,
      String messageGuid,
      String taskHash,
      String task,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    this.idempotencyKey = idempotencyKey;
    this.accountId = accountId;
    this.chatGuid = chatGuid;
    this.messageGuid = messageGuid;
    this.taskHash = taskHash;
    this.task = task;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
