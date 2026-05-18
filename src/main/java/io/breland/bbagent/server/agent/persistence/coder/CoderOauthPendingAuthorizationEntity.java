package io.breland.bbagent.server.agent.persistence.coder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coder_oauth_pending_authorizations")
@Getter
@Setter
@NoArgsConstructor
public class CoderOauthPendingAuthorizationEntity {

  @Id
  @Column(name = "pending_id", nullable = false, length = 128)
  private String pendingId;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "chat_guid", length = 255)
  private String chatGuid;

  @Column(name = "message_guid", length = 255)
  private String messageGuid;

  @Column(name = "code_verifier", nullable = false, columnDefinition = "TEXT")
  private String codeVerifier;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public CoderOauthPendingAuthorizationEntity(
      String pendingId,
      String accountId,
      String chatGuid,
      String messageGuid,
      String codeVerifier,
      Instant expiresAt,
      Instant createdAt) {
    this.pendingId = pendingId;
    this.accountId = accountId;
    this.chatGuid = chatGuid;
    this.messageGuid = messageGuid;
    this.codeVerifier = codeVerifier;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }
}
