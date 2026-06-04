package io.breland.bbagent.server.agent.persistence.nativeapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "native_app_sessions")
@Getter
@Setter
@NoArgsConstructor
public class NativeAppSessionEntity {
  @Id
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "app_account_token", nullable = false, length = 36)
  private String appAccountToken;

  @Column(name = "start_token_hash", nullable = false, length = 64)
  private String startTokenHash;

  @Column(name = "start_token_expires_at", nullable = false)
  private Instant startTokenExpiresAt;

  @Column(name = "claimed_at")
  private Instant claimedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public NativeAppSessionEntity(
      String tokenHash,
      String accountId,
      String appAccountToken,
      String startTokenHash,
      Instant startTokenExpiresAt,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.tokenHash = tokenHash;
    this.accountId = accountId;
    this.appAccountToken = appAccountToken;
    this.startTokenHash = startTokenHash;
    this.startTokenExpiresAt = startTokenExpiresAt;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
