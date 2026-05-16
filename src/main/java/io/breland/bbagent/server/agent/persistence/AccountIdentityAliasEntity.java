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
@Table(name = "agent_account_identity_aliases")
@Getter
@Setter
@NoArgsConstructor
public class AccountIdentityAliasEntity {

  @Id
  @Column(name = "alias_key", nullable = false, length = 768)
  private String aliasKey;

  @Column(name = "account_base", nullable = false, length = 512)
  private String accountBase;

  @Column(name = "transport", nullable = false, length = 64)
  private String transport;

  @Column(name = "identifier", nullable = false, length = 512)
  private String identifier;

  @Column(name = "identifier_type", nullable = false, length = 32)
  private String identifierType;

  @Column(name = "normalized_identifier", nullable = false, length = 512)
  private String normalizedIdentifier;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public AccountIdentityAliasEntity(
      String aliasKey,
      String accountBase,
      String transport,
      String identifier,
      String identifierType,
      String normalizedIdentifier,
      Instant createdAt,
      Instant updatedAt) {
    this.aliasKey = aliasKey;
    this.accountBase = accountBase;
    this.transport = transport;
    this.identifier = identifier;
    this.identifierType = identifierType;
    this.normalizedIdentifier = normalizedIdentifier;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
