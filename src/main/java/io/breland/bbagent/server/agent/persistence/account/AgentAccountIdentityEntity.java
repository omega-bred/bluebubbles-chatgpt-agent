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
@Table(name = "agent_account_identities")
@Getter
@Setter
@NoArgsConstructor
public class AgentAccountIdentityEntity {

  @Id
  @Column(name = "identity_id", nullable = false, length = 36)
  private String identityId;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "identity_type", nullable = false, length = 64)
  private String identityType;

  @Column(name = "identifier", nullable = false, length = 512)
  private String identifier;

  @Column(name = "normalized_identifier", nullable = false, length = 512)
  private String normalizedIdentifier;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public AgentAccountIdentityEntity(
      String identityId,
      String accountId,
      String identityType,
      String identifier,
      String normalizedIdentifier,
      Instant createdAt,
      Instant updatedAt) {
    this.identityId = identityId;
    this.accountId = accountId;
    this.identityType = identityType;
    this.identifier = identifier;
    this.normalizedIdentifier = normalizedIdentifier;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
