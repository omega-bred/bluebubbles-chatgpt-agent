package io.breland.bbagent.server.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "global_contact")
@Getter
@Setter
@NoArgsConstructor
public class GlobalContactEntity {

  @Id
  @Column(name = "sender", nullable = false, length = 255)
  private String sender;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  public GlobalContactEntity(String sender, String name) {
    this.sender = sender;
    this.name = name;
  }
}
