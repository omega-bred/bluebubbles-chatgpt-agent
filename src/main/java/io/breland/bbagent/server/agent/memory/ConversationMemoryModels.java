package io.breland.bbagent.server.agent.memory;

import java.time.Instant;
import java.util.List;

public final class ConversationMemoryModels {
  private ConversationMemoryModels() {}

  public enum ArtifactKind {
    GROUP_DECISION,
    GROUP_FACT
  }

  public enum ArtifactStatus {
    PROVISIONAL,
    CONFIRMED,
    SUPERSEDED,
    DELETED
  }

  public enum ArtifactSensitivity {
    NORMAL,
    SENSITIVE,
    BLOCKED
  }

  public enum ProjectionOperation {
    UPSERT,
    DELETE
  }

  public enum ProjectionState {
    PENDING,
    SUCCEEDED,
    FAILED
  }

  public record JournalMessage(
      String messageGuid,
      String conversationId,
      String senderAccountId,
      String text,
      Instant sourceTimestamp,
      boolean fromAgent,
      boolean systemMessage,
      String contentHash) {}

  public record ExtractionCandidate(
      ArtifactKind kind,
      String text,
      ArtifactStatus status,
      ArtifactSensitivity sensitivity,
      double confidence,
      Instant occurredAt,
      Instant expiresAt,
      List<String> evidenceMessageGuids,
      String supersedesArtifactId,
      String contentHash) {
    public ExtractionCandidate {
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
    }
  }

  public record ExtractionBatch(
      String conversationId,
      List<JournalMessage> sourceMessages,
      List<ExtractionCandidate> candidates,
      String summary,
      String itemPayload,
      String corpusHash,
      Instant processedAt) {
    public ExtractionBatch {
      sourceMessages = List.copyOf(sourceMessages);
      candidates = List.copyOf(candidates);
    }
  }

  public record AuthorizedMemory(
      String artifactId,
      String memory,
      String sourceGroup,
      Instant occurredAt,
      boolean readOnly,
      String memoryId) {}

  public record ConversationRecord(
      String conversationId,
      String transport,
      String externalConversationId,
      boolean group,
      String displayName,
      Instant memoryEnabledAt,
      String memoryEnabledByAccountId,
      Instant lastObservedAt) {}

  public record WorkClaim(String conversationId, String workerId, Instant claimedUntil) {}

  public record ProjectionClaim(
      String artifactId,
      String accountId,
      ProjectionOperation operation,
      String projectionHash,
      String workerId,
      Instant claimedUntil) {}
}
