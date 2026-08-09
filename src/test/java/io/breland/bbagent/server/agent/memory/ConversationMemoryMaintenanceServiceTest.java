package io.breland.bbagent.server.agent.memory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryBacklog;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.MemoryCleanupResult;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ConversationMemoryMaintenanceServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");

  @Test
  void appliesConfiguredRetentionAndPublishesBacklogMetrics() {
    ConversationMemoryStore store = mock(ConversationMemoryStore.class);
    OperationalMetricsService metrics = mock(OperationalMetricsService.class);
    MemoryCleanupResult cleanupResult = new MemoryCleanupResult(2, 3, 4);
    when(store.cleanupMemory(NOW, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(90))))
        .thenReturn(cleanupResult);
    MemoryBacklog backlog = new MemoryBacklog(Duration.ofSeconds(12), Duration.ofSeconds(34), 5);
    when(store.memoryBacklog(NOW)).thenReturn(backlog);
    ConversationMemoryMaintenanceService service =
        new ConversationMemoryMaintenanceService(
            store,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofDays(30),
            Duration.ofDays(90),
            true);

    service.cleanupExpiredMemory();
    service.publishBacklogMetrics();

    verify(store)
        .cleanupMemory(NOW, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(90)));
    verify(metrics).recordMemoryCleanup("raw_message", 2, true, null, Duration.ZERO);
    verify(metrics).recordMemoryCleanup("summary_segment", 3, true, null, Duration.ZERO);
    verify(metrics).recordMemoryCleanup("expired_artifact", 4, true, null, Duration.ZERO);
    verify(metrics).updateMemoryBacklog(Duration.ofSeconds(12), Duration.ofSeconds(34), 5);
  }
}
