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

  @Column(name = "website_preferred_username", length = 255)
  private String websitePreferredUsername;

  @Column(name = "website_display_name", length = 512)
  private String websiteDisplayName;

  @Column(name = "global_contact_name", length = 512)
  private String globalContactName;

  @Column(name = "is_premium", nullable = false)
  private boolean premium;

  @Column(name = "selected_model", length = 128)
  private String selectedModel;

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
