package io.breland.bbagent.server.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "gcal_calendar_access")
@Getter
@Setter
@NoArgsConstructor
public class GcalCalendarAccessEntity {

  @Id
  @Column(name = "id", nullable = false, length = 512)
  private String id;

  @Column(name = "account_key", nullable = false, length = 255)
  private String accountKey;

  @Column(name = "calendar_id", nullable = false, length = 255)
  private String calendarId;

  @Column(name = "mode", nullable = false, length = 32)
  private String mode;

  public GcalCalendarAccessEntity(String id, String accountKey, String calendarId, String mode) {
    this.id = id;
    this.accountKey = accountKey;
    this.calendarId = calendarId;
    this.mode = mode;
  }
}
