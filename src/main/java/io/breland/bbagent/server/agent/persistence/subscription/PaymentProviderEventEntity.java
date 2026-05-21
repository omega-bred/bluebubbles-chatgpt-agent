package io.breland.bbagent.server.agent.persistence.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payment_provider_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentProviderEventEntity {

  @Id
  @Column(name = "event_id", nullable = false, length = 36)
  private String eventId;

  @Column(name = "provider", nullable = false, length = 64)
  private String provider;

  @Column(name = "provider_event_id", nullable = false, length = 255)
  private String providerEventId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "account_id", length = 36)
  private String accountId;

  @Column(name = "checkout_session_id", length = 36)
  private String checkoutSessionId;

  @Column(name = "subscription_id", length = 36)
  private String subscriptionId;

  @Column(name = "provider_subscription_id", length = 255)
  private String providerSubscriptionId;

  @Column(name = "status", nullable = false, length = 64)
  private String status;

  @Column(name = "raw_payload", nullable = false)
  private String rawPayload;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  public PaymentProviderEventEntity(
      String eventId,
      String provider,
      String providerEventId,
      String eventType,
      String status,
      String rawPayload,
      Instant receivedAt) {
    this.eventId = eventId;
    this.provider = provider;
    this.providerEventId = providerEventId;
    this.eventType = eventType;
    this.status = status;
    this.rawPayload = rawPayload;
    this.receivedAt = receivedAt;
  }
}
