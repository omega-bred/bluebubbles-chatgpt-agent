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
@Table(name = "coder_oauth_clients")
@Getter
@Setter
@NoArgsConstructor
public class CoderOauthClientEntity {

  @Id
  @Column(name = "issuer", nullable = false, length = 512)
  private String issuer;

  @Column(name = "client_id", nullable = false, columnDefinition = "TEXT")
  private String clientId;

  @Column(name = "client_secret", columnDefinition = "TEXT")
  private String clientSecret;

  @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
  private String redirectUri;

  @Column(name = "token_endpoint_auth_method", length = 64)
  private String tokenEndpointAuthMethod;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public CoderOauthClientEntity(
      String issuer,
      String clientId,
      String clientSecret,
      String redirectUri,
      String tokenEndpointAuthMethod,
      Instant createdAt,
      Instant updatedAt) {
    this.issuer = issuer;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
