package io.breland.bbagent.server.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "gcal_oauth_credentials")
@Getter
@Setter
@NoArgsConstructor
public class GcalCredentialEntity {

  @Id
  @Column(name = "id", nullable = false, length = 512)
  private String id;

  @Column(name = "store_id", nullable = false, length = 128)
  private String storeId;

  @Column(name = "account_key", nullable = false, length = 255)
  private String accountKey;

  @Column(name = "account_base", length = 255)
  private String accountBase;

  @Column(name = "account_id", length = 255)
  private String accountId;

  @Column(name = "access_token", columnDefinition = "TEXT")
  private String accessToken;

  @Column(name = "refresh_token", columnDefinition = "TEXT")
  private String refreshToken;

  @Column(name = "expiration_time_ms")
  private Long expirationTimeMs;

  public GcalCredentialEntity(
      String id,
      String storeId,
      String accountKey,
      String accountBase,
      String accountId,
      String accessToken,
      String refreshToken,
      Long expirationTimeMs) {
    this.id = id;
    this.storeId = storeId;
    this.accountKey = accountKey;
    this.accountBase = accountBase;
    this.accountId = accountId;
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.expirationTimeMs = expirationTimeMs;
  }
}
