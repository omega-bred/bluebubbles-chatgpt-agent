package io.breland.bbagent.server.agent.persistence.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_accounts")
@Getter
@Setter
@NoArgsConstructor
public class AgentAccountEntity {

  @Id
  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "website_subject", unique = true, length = 255)
  private String websiteSubject;

  @Column(name = "website_email", length = 512)
  private String websiteEmail;

  @Column(name = "website_display_name", length = 512)
  private String websiteDisplayName;

  @Column(name = "global_contact_name", length = 512)
  private String globalContactName;

  @Column(name = "is_premium", nullable = false)
  private boolean premium;

  @Column(name = "premium_entitlement_source", nullable = false, length = 64)
  private String premiumEntitlementSource = "none";

  @Column(name = "premium_subscription_expires_at")
  private Instant premiumSubscriptionExpiresAt;

  @Column(name = "premium_entitlement_synced_at")
  private Instant premiumEntitlementSyncedAt;

  @Column(name = "selected_model", length = 128)
  private String selectedModel;

  @Column(name = "terms_accepted_at")
  private Instant termsAcceptedAt;

  @Column(name = "processing_blocked", nullable = false)
  private boolean processingBlocked;

  @Column(name = "processing_blocked_reason", columnDefinition = "TEXT")
  private String processingBlockedReason;

  @Column(name = "processing_blocked_at")
  private Instant processingBlockedAt;

  @Column(name = "processing_blocked_by", length = 255)
  private String processingBlockedBy;

  @Column(name = "canary_account", nullable = false)
  private boolean canaryAccount;

  @Column(name = "canary_label", length = 128)
  private String canaryLabel;

  @Column(name = "canary_last_seen_at")
  private Instant canaryLastSeenAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public AgentAccountEntity(String accountId, Instant createdAt, Instant updatedAt) {
    this.accountId = accountId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
