package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

public final class ConversationQuestionAnsweringModels {
  private ConversationQuestionAnsweringModels() {}

  public enum AnswerStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    UNAVAILABLE;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum RetrievalMode {
    EXACT_SEARCH,
    CHRONOLOGICAL,
    HYBRID;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum CoverageStatus {
    COMPLETE,
    PARTIAL;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record SearchPlan(
      List<String> terms,
      @Nullable String senderHint,
      @Nullable Instant fromHint,
      @Nullable Instant toHint) {
    public SearchPlan {
      terms = List.copyOf(terms);
      senderHint = StringUtils.trimToNull(senderHint);
    }
  }

  public record QuestionMessage(
      String messageGuid, String participant, Instant timestamp, String text) {
    public QuestionMessage {
      requireNotBlank(messageGuid, "message guid");
      requireNotBlank(participant, "participant");
      if (timestamp == null) {
        throw new IllegalArgumentException("timestamp must not be null");
      }
      requireNotBlank(text, "text");
    }
  }

  public record MembershipInterval(Instant startedAt, @Nullable Instant endedAt) {
    public MembershipInterval {
      if (startedAt == null) {
        throw new IllegalArgumentException("membership start must not be null");
      }
    }

    public boolean contains(Instant timestamp) {
      return timestamp != null
          && !timestamp.isBefore(startedAt)
          && (endedAt == null || timestamp.isBefore(endedAt));
    }
  }

  public record RetrievalRequest(
      String accountId,
      ConversationRecord conversation,
      List<MembershipInterval> memberships,
      Instant from,
      Instant to,
      Instant deadline) {
    public RetrievalRequest {
      requireNotBlank(accountId, "account id");
      if (conversation == null) {
        throw new IllegalArgumentException("conversation must not be null");
      }
      memberships = List.copyOf(memberships);
      if (from == null || to == null || !from.isBefore(to)) {
        throw new IllegalArgumentException("retrieval range must be ordered");
      }
      if (deadline == null) {
        throw new IllegalArgumentException("retrieval deadline must not be null");
      }
    }
  }

  public record RetrievalResult(
      List<QuestionMessage> messages,
      RetrievalMode mode,
      CoverageStatus coverageStatus,
      Instant coverageThrough,
      @Nullable String partialReason,
      int pageCount) {
    public RetrievalResult {
      messages = List.copyOf(messages);
      if (mode == null) {
        throw new IllegalArgumentException("retrieval mode must not be null");
      }
      if (coverageStatus == null) {
        throw new IllegalArgumentException("coverage status must not be null");
      }
      if (coverageThrough == null) {
        throw new IllegalArgumentException("coverage through must not be null");
      }
      partialReason = StringUtils.trimToNull(partialReason);
      if (coverageStatus == CoverageStatus.COMPLETE && partialReason != null) {
        throw new IllegalArgumentException("complete retrieval must not have a partial reason");
      }
      if (coverageStatus == CoverageStatus.PARTIAL && partialReason == null) {
        throw new IllegalArgumentException("partial retrieval must have a reason");
      }
      if (pageCount < 0) {
        throw new IllegalArgumentException("page count must not be negative");
      }
    }
  }

  public record ModelAnswer(
      AnswerStatus status,
      String answer,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      boolean needsMoreContext) {
    public ModelAnswer {
      if (status == null) {
        throw new IllegalArgumentException("answer status must not be null");
      }
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
    }
  }

  public record RoutedModelAnswer(ModelAnswer answer, String model, boolean fallbackUsed) {
    public RoutedModelAnswer {
      if (answer == null) {
        throw new IllegalArgumentException("answer must not be null");
      }
      requireNotBlank(model, "model");
    }

    public RoutedModelAnswer(ModelAnswer answer, String model) {
      this(answer, model, false);
    }
  }

  public record GroupQuestionAnswer(
      AnswerStatus status,
      String answer,
      Confidence confidence,
      @Nullable String model,
      boolean fallbackUsed,
      int evidenceMessageCount,
      RetrievalMode retrievalMode,
      CoverageStatus coverageStatus,
      Instant from,
      Instant to,
      Instant coverageThrough,
      @Nullable String partialReason) {
    public GroupQuestionAnswer {
      if (status == null) {
        throw new IllegalArgumentException("answer status must not be null");
      }
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      model = StringUtils.trimToNull(model);
      if (status == AnswerStatus.ANSWERED && model == null) {
        throw new IllegalArgumentException("answered result must have a model");
      }
      if (fallbackUsed && model == null) {
        throw new IllegalArgumentException("fallback use requires a model");
      }
      if (evidenceMessageCount < 0) {
        throw new IllegalArgumentException("evidence message count must not be negative");
      }
      if (status == AnswerStatus.ANSWERED && evidenceMessageCount == 0) {
        throw new IllegalArgumentException("answered result must have evidence");
      }
      if (retrievalMode == null) {
        throw new IllegalArgumentException("retrieval mode must not be null");
      }
      if (coverageStatus == null) {
        throw new IllegalArgumentException("coverage status must not be null");
      }
      if (from == null || to == null || !from.isBefore(to)) {
        throw new IllegalArgumentException("answer range must be ordered");
      }
      if (coverageThrough == null) {
        throw new IllegalArgumentException("coverage through must not be null");
      }
      partialReason = StringUtils.trimToNull(partialReason);
      if (coverageStatus == CoverageStatus.COMPLETE && partialReason != null) {
        throw new IllegalArgumentException("complete answer must not have a partial reason");
      }
      if (coverageStatus == CoverageStatus.PARTIAL && partialReason == null) {
        throw new IllegalArgumentException("partial answer must have a reason");
      }
    }
  }

  public record QuestionFinding(
      String answer,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      Instant coverageThrough) {
    public QuestionFinding {
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
      if (coverageThrough == null) {
        throw new IllegalArgumentException("coverage through must not be null");
      }
    }
  }

  private static void requireNotBlank(String value, String name) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
