package io.breland.bbagent.server.agent.persistence.website;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "website_account_link_tokens")
@Getter
@Setter
@NoArgsConstructor
public class WebsiteAccountLinkTokenEntity {

  @Id
  @Column(name = "token_hash", nullable = false, length = 128)
  private String tokenHash;

  @Column(name = "account_base", nullable = false, length = 512)
  private String accountBase;

  @Column(name = "coder_account_base", nullable = false, length = 512)
  private String coderAccountBase;

  @Column(name = "gcal_account_base", nullable = false, length = 512)
  private String gcalAccountBase;

  @Column(name = "chat_guid", length = 255)
  private String chatGuid;

  @Column(name = "sender", length = 255)
  private String sender;

  @Column(name = "service", length = 64)
  private String service;

  @Column(name = "is_group", nullable = false)
  private boolean group;

  @Column(name = "source_message_guid", length = 255)
  private String sourceMessageGuid;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "redeemed_at")
  private Instant redeemedAt;

  @Column(name = "redeemed_account_subject", length = 255)
  private String redeemedAccountSubject;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public WebsiteAccountLinkTokenEntity(
      String tokenHash,
      String accountBase,
      String coderAccountBase,
      String gcalAccountBase,
      String chatGuid,
      String sender,
      String service,
      boolean group,
      String sourceMessageGuid,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.tokenHash = tokenHash;
    this.accountBase = accountBase;
    this.coderAccountBase = coderAccountBase;
    this.gcalAccountBase = gcalAccountBase;
    this.chatGuid = chatGuid;
    this.sender = sender;
    this.service = service;
    this.group = group;
    this.sourceMessageGuid = sourceMessageGuid;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
