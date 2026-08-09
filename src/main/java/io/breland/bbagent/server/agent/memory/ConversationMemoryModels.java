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

  public record ExistingArtifact(
      String artifactId,
      ArtifactKind kind,
      String text,
      ArtifactStatus status,
      Instant occurredAt) {}

  public record ExtractionCheckpoint(
      Instant lastProcessedAt, String lastProcessedMessageGuid, String lastCorpusHash) {}

  public record ModelExtraction(
      String summary, List<ExtractionCandidate> candidates, String itemPayload) {
    public ModelExtraction {
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

  public record ProjectionArtifact(
      String artifactId,
      String conversationId,
      String groupDisplayName,
      ArtifactKind kind,
      String text,
      ArtifactStatus status,
      ArtifactSensitivity sensitivity,
      double confidence,
      Instant occurredAt,
      Instant expiresAt) {}

  public record ProjectedArtifact(
      String artifactId,
      String mem0MemoryId,
      String conversationId,
      String groupDisplayName,
      ArtifactKind kind,
      String text,
      ArtifactStatus status,
      ArtifactSensitivity sensitivity,
      double confidence,
      Instant occurredAt,
      Instant expiresAt) {}

  public record DigestWorkClaim(
      String conversationId,
      Instant periodStart,
      Instant periodEnd,
      String workerId,
      Instant claimedUntil) {}

  public record SummaryMaterial(
      String summaryId,
      String summaryType,
      String conversationId,
      String summary,
      String itemPayload,
      Instant windowStart,
      Instant windowEnd,
      Instant coverageThrough,
      String corpusHash) {}

  public record AuthorizedGroup(
      String conversationId, String displayName, Instant lastActivityAt) {}

  public record DigestBatch(
      String conversationId,
      Instant periodStart,
      Instant periodEnd,
      String summary,
      String itemPayload,
      String corpusHash,
      Instant coverageThrough,
      List<String> sourceSegmentIds,
      Instant processedAt) {
    public DigestBatch {
      sourceSegmentIds = List.copyOf(sourceSegmentIds);
    }
  }

  public record CatchupGroup(
      String group,
      String summary,
      List<String> keyDevelopments,
      List<String> decisions,
      List<String> openQuestions,
      Instant from,
      Instant to,
      Instant coverageThrough) {
    public CatchupGroup {
      keyDevelopments = List.copyOf(keyDevelopments);
      decisions = List.copyOf(decisions);
      openQuestions = List.copyOf(openQuestions);
    }
  }

  public record CatchupResult(List<CatchupGroup> groups, List<String> disambiguationOptions) {
    public CatchupResult {
      groups = List.copyOf(groups);
      disambiguationOptions = List.copyOf(disambiguationOptions);
    }

    public boolean ambiguous() {
      return !disambiguationOptions.isEmpty();
    }
  }
}
