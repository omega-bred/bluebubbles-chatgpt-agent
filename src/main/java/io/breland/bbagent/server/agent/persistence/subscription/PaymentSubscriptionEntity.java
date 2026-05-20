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
@Table(name = "payment_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentSubscriptionEntity {

  @Id
  @Column(name = "subscription_id", nullable = false, length = 36)
  private String subscriptionId;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "provider", nullable = false, length = 64)
  private String provider;

  @Column(name = "plan_key", nullable = false, length = 128)
  private String planKey;

  @Column(name = "provider_subscription_id", length = 255)
  private String providerSubscriptionId;

  @Column(name = "provider_customer_id", length = 255)
  private String providerCustomerId;

  @Column(name = "provider_customer_selector", length = 512)
  private String providerCustomerSelector;

  @Column(name = "provider_offering_id", length = 255)
  private String providerOfferingId;

  @Column(name = "provider_plan_id", length = 255)
  private String providerPlanId;

  @Column(name = "provider_status", length = 128)
  private String providerStatus;

  @Column(name = "status", nullable = false, length = 64)
  private String status;

  @Column(name = "current_period_start")
  private Instant currentPeriodStart;

  @Column(name = "current_period_end")
  private Instant currentPeriodEnd;

  @Column(name = "trial_end")
  private Instant trialEnd;

  @Column(name = "grace_period_end")
  private Instant gracePeriodEnd;

  @Column(name = "canceled_at")
  private Instant canceledAt;

  @Column(name = "cancel_at_period_end", nullable = false)
  private boolean cancelAtPeriodEnd;

  @Column(name = "checkout_session_id", length = 36)
  private String checkoutSessionId;

  @Column(name = "management_url")
  private String managementUrl;

  @Column(name = "raw_payload")
  private String rawPayload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "last_synced_at")
  private Instant lastSyncedAt;

  public PaymentSubscriptionEntity(
      String subscriptionId,
      String accountId,
      String provider,
      String planKey,
      String providerCustomerSelector,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    this.subscriptionId = subscriptionId;
    this.accountId = accountId;
    this.provider = provider;
    this.planKey = planKey;
    this.providerCustomerSelector = providerCustomerSelector;
    this.status = status;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
