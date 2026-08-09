package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryBacklog;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryCleanupResult;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryMaintenanceService {
  private final ConversationMemoryStore store;
  private final @Nullable OperationalMetricsService metrics;
  private final Clock clock;
  private final Duration rawRetention;
  private final Duration segmentRetention;
  private final boolean globallyEnabled;

  @Autowired
  public ConversationMemoryMaintenanceService(
      ConversationMemoryStore store,
      @Nullable OperationalMetricsService metrics,
      @Nullable Clock clock,
      @Value("${bbagent.memory.group.raw-retention:P30D}") Duration rawRetention,
      @Value("${bbagent.memory.group.segment-retention:P90D}") Duration segmentRetention,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this.store = store;
    this.metrics = metrics;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.rawRetention = rawRetention == null ? Duration.ofDays(30) : rawRetention;
    this.segmentRetention = segmentRetention == null ? Duration.ofDays(90) : segmentRetention;
    this.globallyEnabled = globallyEnabled;
  }

  @Scheduled(cron = "${bbagent.memory.group.cleanup-cron:0 45 3 * * *}", zone = "UTC")
  public void cleanupExpiredMemory() {
    if (!globallyEnabled) {
      return;
    }
    Instant startedAt = clock.instant();
    try {
      MemoryCleanupResult result =
          store.cleanupMemory(
              startedAt, startedAt.minus(rawRetention), startedAt.minus(segmentRetention));
      Duration duration = Duration.between(startedAt, clock.instant());
      if (metrics != null) {
        metrics.recordMemoryCleanup(
            "raw_message", result.rawMessagesCleared(), true, null, duration);
        metrics.recordMemoryCleanup(
            "summary_segment", result.segmentsDeleted(), true, null, duration);
        metrics.recordMemoryCleanup(
            "expired_artifact", result.artifactsExpired(), true, null, duration);
      }
    } catch (RuntimeException e) {
      if (metrics != null) {
        metrics.recordMemoryCleanup(
            "all",
            0,
            false,
            OperationalMetricsService.failureType(e),
            Duration.between(startedAt, clock.instant()));
      }
      throw e;
    }
  }

  @Scheduled(
      fixedDelayString = "${bbagent.memory.group.metrics-poll-interval:PT1M}",
      initialDelayString = "${bbagent.memory.group.metrics-initial-delay:PT30S}")
  public void publishBacklogMetrics() {
    if (!globallyEnabled || metrics == null) {
      return;
    }
    MemoryBacklog backlog = store.memoryBacklog(clock.instant());
    metrics.updateMemoryBacklog(
        backlog.oldestExtractionAge(), backlog.oldestProjectionAge(), backlog.failedWorkCount());
  }
}
