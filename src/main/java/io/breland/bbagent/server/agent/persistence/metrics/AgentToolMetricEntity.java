package io.breland.bbagent.server.agent.persistence.metrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_tool_metrics")
@Getter
@Setter
@NoArgsConstructor
public class AgentToolMetricEntity {

  @Id
  @Column(name = "id", nullable = false, length = 36)
  private String id;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "transport", nullable = false, length = 64)
  private String transport;

  @Column(name = "message_guid", length = 255)
  private String messageGuid;

  @Column(name = "chat_guid_hash", length = 128)
  private String chatGuidHash;

  @Column(name = "user_key_hash", nullable = false, length = 128)
  private String userKeyHash;

  @Column(name = "tool_name", nullable = false, length = 128)
  private String toolName;

  @Column(name = "tool_category", nullable = false, length = 64)
  private String toolCategory;

  @Column(name = "success", nullable = false)
  private boolean success;

  @Column(name = "failure_type", length = 64)
  private String failureType;

  @Column(name = "duration_ms", nullable = false)
  private long durationMillis;

  @Column(name = "model_key", nullable = false, length = 128)
  private String modelKey;

  @Column(name = "model_label", nullable = false, length = 255)
  private String modelLabel;

  @Column(name = "responses_model", nullable = false, length = 255)
  private String responsesModel;

  @Column(name = "plan", nullable = false, length = 64)
  private String plan;

  @Column(name = "is_premium", nullable = false)
  private boolean premium;

  @Column(name = "account_type", nullable = false, length = 64)
  private String accountType;

  @Column(name = "workflow_mode", nullable = false, length = 64)
  private String workflowMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public AgentToolMetricEntity(
      String id,
      Instant occurredAt,
      String transport,
      String messageGuid,
      String chatGuidHash,
      String userKeyHash,
      String toolName,
      String toolCategory,
      boolean success,
      String failureType,
      long durationMillis,
      String modelKey,
      String modelLabel,
      String responsesModel,
      String plan,
      boolean premium,
      String accountType,
      String workflowMode,
      Instant createdAt) {
    this.id = id;
    this.occurredAt = occurredAt;
    this.transport = transport;
    this.messageGuid = messageGuid;
    this.chatGuidHash = chatGuidHash;
    this.userKeyHash = userKeyHash;
    this.toolName = toolName;
    this.toolCategory = toolCategory;
    this.success = success;
    this.failureType = failureType;
    this.durationMillis = durationMillis;
    this.modelKey = modelKey;
    this.modelLabel = modelLabel;
    this.responsesModel = responsesModel;
    this.plan = plan;
    this.premium = premium;
    this.accountType = accountType;
    this.workflowMode = workflowMode;
    this.createdAt = createdAt;
  }
}
