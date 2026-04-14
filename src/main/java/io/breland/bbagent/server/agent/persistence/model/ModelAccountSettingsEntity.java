package io.breland.bbagent.server.agent.persistence.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_model_account_settings")
@Getter
@Setter
@NoArgsConstructor
public class ModelAccountSettingsEntity {

  @Id
  @Column(name = "account_base", nullable = false, length = 512)
  private String accountBase;

  @Column(name = "is_premium", nullable = false)
  private boolean premium;

  @Column(name = "selected_model", length = 128)
  private String selectedModel;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public ModelAccountSettingsEntity(
      String accountBase,
      boolean premium,
      String selectedModel,
      Instant createdAt,
      Instant updatedAt) {
    this.accountBase = accountBase;
    this.premium = premium;
    this.selectedModel = selectedModel;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
