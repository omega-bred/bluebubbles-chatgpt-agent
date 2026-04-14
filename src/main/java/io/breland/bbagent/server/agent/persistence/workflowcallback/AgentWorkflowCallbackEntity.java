package io.breland.bbagent.server.agent.persistence.workflowcallback;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_workflow_callbacks")
public class AgentWorkflowCallbackEntity {
  @Id private String callbackId;
  private String signingSecret;
  private String chatGuid;
  private String sourceMessageGuid;
  private String threadOriginatorGuid;
  private String service;
  private String sender;
  private boolean isGroup;
  private String purpose;
  private String resumeInstructions;
  private String status;
  private Instant expiresAt;
  private String receivedWebhookId;
  private Instant receivedAt;
  private Instant createdAt;
  private Instant updatedAt;

  public AgentWorkflowCallbackEntity() {}

  public AgentWorkflowCallbackEntity(
      String callbackId,
      String signingSecret,
      String chatGuid,
      String sourceMessageGuid,
      String threadOriginatorGuid,
      String service,
      String sender,
      boolean isGroup,
      String purpose,
      String resumeInstructions,
      String status,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.callbackId = callbackId;
    this.signingSecret = signingSecret;
    this.chatGuid = chatGuid;
    this.sourceMessageGuid = sourceMessageGuid;
    this.threadOriginatorGuid = threadOriginatorGuid;
    this.service = service;
    this.sender = sender;
    this.isGroup = isGroup;
    this.purpose = purpose;
    this.resumeInstructions = resumeInstructions;
    this.status = status;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getCallbackId() {
    return callbackId;
  }

  public String getSigningSecret() {
    return signingSecret;
  }

  public String getChatGuid() {
    return chatGuid;
  }

  public String getSourceMessageGuid() {
    return sourceMessageGuid;
  }

  public String getThreadOriginatorGuid() {
    return threadOriginatorGuid;
  }

  public String getService() {
    return service;
  }

  public String getSender() {
    return sender;
  }

  public boolean isGroup() {
    return isGroup;
  }

  public String getPurpose() {
    return purpose;
  }

  public String getResumeInstructions() {
    return resumeInstructions;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getReceivedWebhookId() {
    return receivedWebhookId;
  }

  public void setReceivedWebhookId(String receivedWebhookId) {
    this.receivedWebhookId = receivedWebhookId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(Instant receivedAt) {
    this.receivedAt = receivedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
