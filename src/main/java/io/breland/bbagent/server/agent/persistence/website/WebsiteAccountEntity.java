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
@Table(name = "website_accounts")
@Getter
@Setter
@NoArgsConstructor
public class WebsiteAccountEntity {

  @Id
  @Column(name = "keycloak_subject", nullable = false, length = 255)
  private String keycloakSubject;

  @Column(name = "email", length = 512)
  private String email;

  @Column(name = "preferred_username", length = 255)
  private String preferredUsername;

  @Column(name = "display_name", length = 512)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public WebsiteAccountEntity(
      String keycloakSubject,
      String email,
      String preferredUsername,
      String displayName,
      Instant createdAt,
      Instant updatedAt) {
    this.keycloakSubject = keycloakSubject;
    this.email = email;
    this.preferredUsername = preferredUsername;
    this.displayName = displayName;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
