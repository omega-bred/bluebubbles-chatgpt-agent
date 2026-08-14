package io.breland.bbagent.server.agent.persistence.ratelimit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_rate_limit_usage")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AppRateLimitUsageEntity {

  @Id
  @Column(name = "id", nullable = false, length = 36)
  private String id;

  @Column(name = "limit_key", nullable = false, length = 128)
  private String limitKey;

  @Column(name = "scope_type", nullable = false, length = 64)
  private String scopeType;

  @Column(name = "scope_key", nullable = false, length = 255)
  private String scopeKey;

  @Column(name = "window_start", nullable = false)
  private Instant windowStart;

  @Column(name = "window_end", nullable = false)
  private Instant windowEnd;

  @Column(name = "amount", nullable = false)
  private long amount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
