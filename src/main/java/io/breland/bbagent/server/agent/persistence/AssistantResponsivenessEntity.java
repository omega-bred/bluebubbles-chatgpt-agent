package io.breland.bbagent.server.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "assistant_responsiveness")
@Getter
@Setter
@NoArgsConstructor
public class AssistantResponsivenessEntity {

  @Id
  @Column(name = "chat_guid", nullable = false, length = 255)
  private String chatGuid;

  @Column(name = "responsiveness", nullable = false, length = 64)
  private String responsiveness;

  public AssistantResponsivenessEntity(String chatGuid, String responsiveness) {
    this.chatGuid = chatGuid;
    this.responsiveness = responsiveness;
  }
}
