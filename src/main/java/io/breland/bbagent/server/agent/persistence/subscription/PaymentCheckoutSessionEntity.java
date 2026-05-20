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
@Table(name = "payment_checkout_sessions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentCheckoutSessionEntity {

  @Id
  @Column(name = "checkout_session_id", nullable = false, length = 36)
  private String checkoutSessionId;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "provider", nullable = false, length = 64)
  private String provider;

  @Column(name = "plan_key", nullable = false, length = 128)
  private String planKey;

  @Column(name = "provider_checkout_id", length = 255)
  private String providerCheckoutId;

  @Column(name = "checkout_url")
  private String checkoutUrl;

  @Column(name = "status", nullable = false, length = 64)
  private String status;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "provider_payload")
  private String providerPayload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public PaymentCheckoutSessionEntity(
      String checkoutSessionId,
      String accountId,
      String provider,
      String planKey,
      String status,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.checkoutSessionId = checkoutSessionId;
    this.accountId = accountId;
    this.provider = provider;
    this.planKey = planKey;
    this.status = status;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
