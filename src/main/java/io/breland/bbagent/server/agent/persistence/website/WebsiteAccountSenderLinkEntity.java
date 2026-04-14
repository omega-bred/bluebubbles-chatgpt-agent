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
@Table(name = "website_account_sender_links")
@Getter
@Setter
@NoArgsConstructor
public class WebsiteAccountSenderLinkEntity {

  @Id
  @Column(name = "link_id", nullable = false, length = 36)
  private String linkId;

  @Column(name = "account_subject", nullable = false, length = 255)
  private String accountSubject;

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

  @Column(name = "linked_via_token_hash", length = 128)
  private String linkedViaTokenHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public WebsiteAccountSenderLinkEntity(
      String linkId,
      String accountSubject,
      String accountBase,
      String coderAccountBase,
      String gcalAccountBase,
      String chatGuid,
      String sender,
      String service,
      boolean group,
      String sourceMessageGuid,
      String linkedViaTokenHash,
      Instant createdAt,
      Instant updatedAt) {
    this.linkId = linkId;
    this.accountSubject = accountSubject;
    this.accountBase = accountBase;
    this.coderAccountBase = coderAccountBase;
    this.gcalAccountBase = gcalAccountBase;
    this.chatGuid = chatGuid;
    this.sender = sender;
    this.service = service;
    this.group = group;
    this.sourceMessageGuid = sourceMessageGuid;
    this.linkedViaTokenHash = linkedViaTokenHash;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
