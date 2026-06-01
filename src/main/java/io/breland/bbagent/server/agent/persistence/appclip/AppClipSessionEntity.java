package io.breland.bbagent.server.agent.persistence.appclip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_clip_sessions")
@Getter
@Setter
@NoArgsConstructor
public class AppClipSessionEntity {
  @Id
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "purpose", nullable = false, length = 64)
  private String purpose = "account_link";

  @Column(name = "chat_guid", length = 255)
  private String chatGuid;

  @Column(name = "source_link_token_hash", length = 64)
  private String sourceLinkTokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public AppClipSessionEntity(
      String tokenHash,
      String accountId,
      String sourceLinkTokenHash,
      Instant expiresAt,
      Instant createdAt) {
    this(tokenHash, accountId, "account_link", null, sourceLinkTokenHash, expiresAt, createdAt);
  }

  public AppClipSessionEntity(
      String tokenHash,
      String accountId,
      String purpose,
      String chatGuid,
      String sourceLinkTokenHash,
      Instant expiresAt,
      Instant createdAt) {
    this.tokenHash = tokenHash;
    this.accountId = accountId;
    this.purpose = purpose;
    this.chatGuid = chatGuid;
    this.sourceLinkTokenHash = sourceLinkTokenHash;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }
}
