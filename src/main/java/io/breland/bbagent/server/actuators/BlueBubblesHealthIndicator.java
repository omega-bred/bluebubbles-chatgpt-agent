package io.breland.bbagent.server.actuators;

import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
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
      var account = bbHttpClientWrapper.getAccount();
      if (!account.getLoginStatusMessage().equals("Connected")) {
        log.warn("iCloudAccount was not marked as connected {}", account.toString());
        throw new RuntimeException("Account is not marked as connected");
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
