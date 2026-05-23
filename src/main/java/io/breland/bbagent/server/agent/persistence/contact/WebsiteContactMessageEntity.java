package io.breland.bbagent.server.agent.persistence.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "website_contact_messages")
@Getter
@Setter
@NoArgsConstructor
public class WebsiteContactMessageEntity {

  @Id
  @Column(name = "message_id", nullable = false, length = 36)
  private String messageId;

  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "email", nullable = false, length = 320)
  private String email;

  @Column(name = "subject", nullable = false, length = 200)
  private String subject;

  @Column(name = "message", nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "status", nullable = false, length = 64)
  private String status;

  @Column(name = "remote_address", length = 255)
  private String remoteAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "cap_verified", nullable = false)
  private boolean capVerified;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public WebsiteContactMessageEntity(
      String messageId,
      Instant submittedAt,
      String name,
      String email,
      String subject,
      String message,
      String status,
      String remoteAddress,
      String userAgent,
      boolean capVerified,
      Instant createdAt,
      Instant updatedAt) {
    this.messageId = messageId;
    this.submittedAt = submittedAt;
    this.name = name;
    this.email = email;
    this.subject = subject;
    this.message = message;
    this.status = status;
    this.remoteAddress = remoteAddress;
    this.userAgent = userAgent;
    this.capVerified = capVerified;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
