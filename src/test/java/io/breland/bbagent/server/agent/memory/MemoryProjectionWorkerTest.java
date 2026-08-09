package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactKind;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactSensitivity;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ArtifactStatus;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ProjectionOperation;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryProjectionWorkerTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final Mem0Client mem0Client = mock(Mem0Client.class);
  private final OperationalMetricsService metrics = mock(OperationalMetricsService.class);
  private final ProjectionClaim upsertClaim =
      new ProjectionClaim(
          "artifact-1",
          "account-1",
          ProjectionOperation.UPSERT,
          "projection-hash",
          "worker-1",
          NOW.plusSeconds(300));
  private final MemoryProjectionWorker worker =
      new MemoryProjectionWorker(
          store, mem0Client, metrics, Clock.fixed(NOW, ZoneOffset.UTC), "worker-1");

  @Test
  void projectsAConfirmedArtifactToTheSnapshottedAccount() {
    when(store.claimDueProjections("worker-1", NOW, 25)).thenReturn(List.of(upsertClaim));
    when(store.findProjectionArtifact("artifact-1")).thenReturn(Optional.of(artifact()));
    when(store.isInArtifactAudience("artifact-1", "account-1")).thenReturn(true);
    when(mem0Client.addMemory(
            org.mockito.ArgumentMatchers.eq("account:account-1"),
            org.mockito.ArgumentMatchers.anyString(),
            anyMap()))
        .thenReturn(new Mem0Client.MemoryMutationResult(true, "memory-1"));

    worker.processDueProjections();

    ArgumentCaptor<String> memory = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
    verify(mem0Client)
        .addMemory(
            org.mockito.ArgumentMatchers.eq("account:account-1"),
            memory.capture(),
            metadata.capture());
    assertThat(memory.getValue())
        .isEqualTo("Collective group decision (Trip planning, 2026-08-08): Meet Saturday at 6 PM.");
    assertThat(metadata.getValue())
        .containsEntry("artifact_id", "artifact-1")
        .containsEntry("conversation_id", "conversation-1")
        .containsEntry("source", "bbagent_group_memory");
    assertThat(metadata.getValue().toString())
        .doesNotContain("iMessage", "account-1", "+1555", "Meet Saturday");
    verify(store).completeProjection(upsertClaim, "memory-1", NOW);
    verify(metrics).recordMemoryProjection("UPSERT", true, null, Duration.ZERO);
  }

  @Test
  void failedMem0WriteLeavesTheProjectionRetryable() {
    when(store.claimDueProjections("worker-1", NOW, 25)).thenReturn(List.of(upsertClaim));
    when(store.findProjectionArtifact("artifact-1")).thenReturn(Optional.of(artifact()));
    when(store.isInArtifactAudience("artifact-1", "account-1")).thenReturn(true);
    when(mem0Client.addMemory(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            anyMap()))
        .thenReturn(new Mem0Client.MemoryMutationResult(false, null));

    worker.processDueProjections();

    verify(store).failProjection(upsertClaim, NOW, "mem0_write_failed");
    verify(metrics).recordMemoryProjection("UPSERT", false, "mem0_write_failed", Duration.ZERO);
    verify(store, never()).completeProjection(upsertClaim, null, NOW);
  }

  @Test
  void deleteProjectionRemovesTheExistingMem0Memory() {
    ProjectionClaim deleteClaim =
        new ProjectionClaim(
            "artifact-1",
            "account-1",
            ProjectionOperation.DELETE,
            "projection-hash",
            "worker-1",
            NOW.plusSeconds(300));
    when(store.claimDueProjections("worker-1", NOW, 25)).thenReturn(List.of(deleteClaim));
    when(store.projectionMemoryId("artifact-1", "account-1")).thenReturn(Optional.of("memory-1"));
    when(mem0Client.deleteMemory("memory-1")).thenReturn(true);

    worker.processDueProjections();

    verify(mem0Client).deleteMemory("memory-1");
    verify(store).completeProjection(deleteClaim, null, NOW);
  }

  @Test
  void globalFeatureGuardKeepsUpsertsRetryableWithoutCallingMem0() {
    when(store.claimDueProjections("worker-1", NOW, 25)).thenReturn(List.of(upsertClaim));
    MemoryProjectionWorker disabled =
        new MemoryProjectionWorker(
            store, mem0Client, metrics, Clock.fixed(NOW, ZoneOffset.UTC), "worker-1", 0.85, false);

    disabled.processDueProjections();

    verify(store).failProjection(upsertClaim, NOW, "group_memory_disabled");
    verify(mem0Client, never()).addMemory(anyString(), anyString(), anyMap());
    verify(metrics).recordMemoryProjection("UPSERT", false, "group_memory_disabled", Duration.ZERO);
  }

  private static ProjectionArtifact artifact() {
    return new ProjectionArtifact(
        "artifact-1",
        "conversation-1",
        "Trip planning",
        ArtifactKind.GROUP_DECISION,
        "Meet Saturday at 6 PM.",
        ArtifactStatus.CONFIRMED,
        ArtifactSensitivity.NORMAL,
        0.96,
        NOW.minusSeconds(60),
        null);
  }
}
