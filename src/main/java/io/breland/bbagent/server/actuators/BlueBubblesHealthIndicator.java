package io.breland.bbagent.server.actuators;

import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BlueBubblesHealthIndicator implements HealthIndicator {
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final AtomicReference<Health> health = new AtomicReference<>(Health.up().build());

  public BlueBubblesHealthIndicator(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Scheduled(fixedDelayString = "PT15S")
  public void pingBB() {
    try {
      boolean success = bbHttpClientWrapper.ping();
      if (!success) {
        throw new RuntimeException("Ping failed");
      }
      health.set(Health.up().build());
    } catch (Exception e) {
      health.set(Health.status("DEGRADED").withDetail("error", e.getMessage()).build());
    }
  }

  @Override
  public Health health() {
    return health.get();
  }
}
