package io.breland.bbagent.server.actuators;

import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BlueBubblesHealthIndicator implements HealthIndicator {
  private static final String ICLOUD_CONNECTED_STATUS = "Connected";
  private static final String UNKNOWN_STATUS = "unknown";

  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final @Nullable OperationalMetricsService operationalMetricsService;
  private final AtomicReference<Health> health =
      new AtomicReference<>(Health.up().withDetail("status", "not_checked_yet").build());

  public BlueBubblesHealthIndicator(
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable OperationalMetricsService operationalMetricsService) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.operationalMetricsService = operationalMetricsService;
  }

  @Scheduled(fixedDelayString = "PT15S")
  public void pingBB() {
    Instant checkedAt = Instant.now();
    long startedNanos = System.nanoTime();
    boolean pingSucceeded = false;
    String loginStatusMessage = null;
    try {
      pingSucceeded = bbHttpClientWrapper.ping();
      if (!pingSucceeded) {
        throw new RuntimeException("Ping failed");
      }
      var account = bbHttpClientWrapper.getAccount();
      loginStatusMessage = account.getLoginStatusMessage();
      boolean iCloudConnected = ICLOUD_CONNECTED_STATUS.equals(loginStatusMessage);
      if (!iCloudConnected) {
        log.warn("iCloudAccount was not marked as connected {}", account);
        throw new RuntimeException("Account is not marked as connected");
      }
      recordHealthMetric(true, true, null, startedNanos);
      health.set(
          Health.up()
              .withDetail("lastCheckedAt", checkedAt.toString())
              .withDetail("ping", "ok")
              .withDetail("icloudLoginStatus", loginStatusMessage)
              .build());
    } catch (Exception e) {
      recordHealthMetric(
          false,
          ICLOUD_CONNECTED_STATUS.equals(loginStatusMessage),
          OperationalMetricsService.failureType(e),
          startedNanos);
      health.set(
          Health.status("DEGRADED")
              .withDetail("lastCheckedAt", checkedAt.toString())
              .withDetail("ping", pingSucceeded ? "ok" : "failed")
              .withDetail("icloudLoginStatus", Objects.toString(loginStatusMessage, UNKNOWN_STATUS))
              .withDetail("error", Objects.toString(e.getMessage(), e.getClass().getSimpleName()))
              .build());
      log.warn("Health ping failed - check bb server", e);
    }
  }

  private void recordHealthMetric(
      boolean healthy, boolean iCloudConnected, String failureType, long startedNanos) {
    if (operationalMetricsService == null) {
      return;
    }
    operationalMetricsService.recordBlueBubblesHealthCheck(
        healthy, iCloudConnected, failureType, Duration.ofNanos(System.nanoTime() - startedNanos));
  }

  @Override
  public Health health() {
    return health.get();
  }
}
