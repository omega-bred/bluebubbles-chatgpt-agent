package io.breland.bbagent.server.agent.memory;

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
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MemoryProjectionWorker {
  private static final int CLAIM_LIMIT = 25;
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

  private final ConversationMemoryStore store;
  private final Mem0Client mem0Client;
  private final @Nullable OperationalMetricsService metrics;
  private final Clock clock;
  private final String workerId;
  private final double minimumConfidence;
  private final boolean globallyEnabled;

  @Autowired
  public MemoryProjectionWorker(
      ConversationMemoryStore store,
      Mem0Client mem0Client,
      @Nullable OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.minimum-confidence:0.85}") double minimumConfidence,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this(
        store,
        mem0Client,
        metrics,
        Clock.systemUTC(),
        UUID.randomUUID().toString(),
        minimumConfidence,
        globallyEnabled);
  }

  MemoryProjectionWorker(
      ConversationMemoryStore store,
      Mem0Client mem0Client,
      @Nullable OperationalMetricsService metrics,
      Clock clock,
      String workerId) {
    this(store, mem0Client, metrics, clock, workerId, 0.85, true);
  }

  MemoryProjectionWorker(
      ConversationMemoryStore store,
      Mem0Client mem0Client,
      @Nullable OperationalMetricsService metrics,
      Clock clock,
      String workerId,
      double minimumConfidence,
      boolean globallyEnabled) {
    this.store = store;
    this.mem0Client = mem0Client;
    this.metrics = metrics;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.workerId = workerId;
    this.minimumConfidence = minimumConfidence;
    this.globallyEnabled = globallyEnabled;
  }

  @Scheduled(
      fixedDelayString = "${bbagent.memory.projection.worker-poll-interval:PT5S}",
      initialDelayString = "${bbagent.memory.projection.worker-initial-delay:PT15S}")
  public void processDueProjections() {
    Instant now = clock.instant();
    for (ProjectionClaim claim : store.claimDueProjections(workerId, now, CLAIM_LIMIT)) {
      process(claim, now);
    }
  }

  private void process(ProjectionClaim claim, Instant now) {
    Instant startedAt = clock.instant();
    try {
      String failureType;
      if (claim.operation() == ProjectionOperation.DELETE) {
        failureType = deleteProjection(claim, now);
      } else {
        failureType = upsertProjection(claim, now);
      }
      if (metrics != null) {
        metrics.recordMemoryProjection(
            claim.operation().name(),
            failureType == null,
            failureType,
            Duration.between(startedAt, clock.instant()));
      }
    } catch (RuntimeException e) {
      String failureType = OperationalMetricsService.failureType(e);
      store.failProjection(claim, now, failureType);
      if (metrics != null) {
        metrics.recordMemoryProjection(
            claim.operation().name(),
            false,
            failureType,
            Duration.between(startedAt, clock.instant()));
      }
    }
  }

  private @Nullable String upsertProjection(ProjectionClaim claim, Instant now) {
    if (!globallyEnabled) {
      store.failProjection(claim, now, "group_memory_disabled");
      return "group_memory_disabled";
    }
    Optional<ProjectionArtifact> artifactValue = store.findProjectionArtifact(claim.artifactId());
    if (artifactValue.isEmpty()) {
      store.completeProjection(claim, null, now);
      return null;
    }
    ProjectionArtifact artifact = artifactValue.get();
    if (!isEligible(artifact, now)
        || !store.isInArtifactAudience(claim.artifactId(), claim.accountId())) {
      store.completeProjection(claim, null, now);
      return null;
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("artifact_id", artifact.artifactId());
    metadata.put("conversation_id", artifact.conversationId());
    metadata.put("kind", artifact.kind().name());
    metadata.put("occurred_at", artifact.occurredAt().toString());
    metadata.put("source", "bbagent_group_memory");
    Mem0Client.MemoryMutationResult result =
        mem0Client.addMemory("account:" + claim.accountId(), projectionText(artifact), metadata);
    if (!result.success() || StringUtils.isBlank(result.memoryId())) {
      store.failProjection(claim, now, "mem0_write_failed");
      return "mem0_write_failed";
    }
    store.completeProjection(claim, result.memoryId(), now);
    return null;
  }

  private @Nullable String deleteProjection(ProjectionClaim claim, Instant now) {
    Optional<String> memoryId = store.projectionMemoryId(claim.artifactId(), claim.accountId());
    if (memoryId.isPresent() && !mem0Client.deleteMemory(memoryId.get())) {
      store.failProjection(claim, now, "mem0_delete_failed");
      return "mem0_delete_failed";
    }
    store.completeProjection(claim, null, now);
    return null;
  }

  private boolean isEligible(ProjectionArtifact artifact, Instant now) {
    return artifact.status() == ArtifactStatus.CONFIRMED
        && artifact.sensitivity() == ArtifactSensitivity.NORMAL
        && artifact.confidence() >= minimumConfidence
        && (artifact.expiresAt() == null || artifact.expiresAt().isAfter(now));
  }

  private String projectionText(ProjectionArtifact artifact) {
    String kind =
        artifact.kind() == ConversationMemoryModels.ArtifactKind.GROUP_DECISION
            ? "decision"
            : "fact";
    String groupName =
        StringUtils.defaultIfBlank(artifact.groupDisplayName(), "Group conversation");
    return "Collective group "
        + kind
        + " ("
        + groupName
        + ", "
        + DATE_FORMAT.format(artifact.occurredAt())
        + "): "
        + artifact.text();
  }
}
