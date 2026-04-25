package io.breland.bbagent.server.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "message_ingress_events")
@Getter
@Setter
@NoArgsConstructor
public class MessageIngressEventEntity {
  @Id
  @Column(name = "id", nullable = false, length = 36)
  private String id;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "payload_json", columnDefinition = "TEXT")
  private String payloadJson;

  @Column(name = "normalized_message_json", columnDefinition = "TEXT")
  private String normalizedMessageJson;

  @Column(name = "error_code", length = 128)
  private String errorCode;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "processed_at")
  private Instant processedAt;
}
