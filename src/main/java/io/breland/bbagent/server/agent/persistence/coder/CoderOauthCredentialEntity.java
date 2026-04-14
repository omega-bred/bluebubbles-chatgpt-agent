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
@Table(name = "coder_oauth_credentials")
@Getter
@Setter
@NoArgsConstructor
public class CoderOauthCredentialEntity {

  @Id
  @Column(name = "account_base", nullable = false, length = 512)
  private String accountBase;

  @Column(name = "access_token", columnDefinition = "TEXT")
  private String accessToken;

  @Column(name = "refresh_token", columnDefinition = "TEXT")
  private String refreshToken;

  @Column(name = "token_type", length = 64)
  private String tokenType;

  @Column(name = "scopes", columnDefinition = "TEXT")
  private String scopes;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public CoderOauthCredentialEntity(
      String accountBase,
      String accessToken,
      String refreshToken,
      String tokenType,
      String scopes,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.accountBase = accountBase;
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.tokenType = tokenType;
    this.scopes = scopes;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
