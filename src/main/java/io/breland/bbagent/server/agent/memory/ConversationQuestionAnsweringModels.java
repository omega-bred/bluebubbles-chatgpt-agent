package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

  public enum WindowAction {
    ANSWERED,
    NEED_OLDER_MESSAGES,
    NEED_TIME_CLARIFICATION,
    NO_ANSWER
  }

  enum HistorySource {
    BLUEBUBBLES,
    JOURNAL
  }

  record HistoryWindowCursor(
      HistorySource source,
      int membershipIndex,
      int rawOffset,
      @Nullable Instant journalBeforeTimestamp,
      @Nullable String journalBeforeGuid) {
    HistoryWindowCursor {
      Objects.requireNonNull(source, "history source");
      if (membershipIndex < 0 || rawOffset < 0) {
        throw new IllegalArgumentException("history cursor values must not be negative");
      }
      journalBeforeGuid = StringUtils.trimToNull(journalBeforeGuid);
      if ((journalBeforeTimestamp == null) != (journalBeforeGuid == null)) {
        throw new IllegalArgumentException("journal cursor must be complete");
      }
      if (source == HistorySource.JOURNAL && rawOffset != 0) {
        throw new IllegalArgumentException("journal cursor must not contain a raw offset");
      }
    }
  }

  record HistoryWindow(
      List<QuestionMessage> messages,
      @Nullable HistoryWindowCursor nextCursor,
      boolean sourceExhausted,
      boolean windowComplete,
      @Nullable String partialReason,
      int pageCount) {
    HistoryWindow {
      messages = List.copyOf(messages);
      partialReason = StringUtils.trimToNull(partialReason);
      if (pageCount < 0) {
        throw new IllegalArgumentException("history window page count must not be negative");
      }
      if (windowComplete == (partialReason != null)) {
        throw new IllegalArgumentException("history window completion state is inconsistent");
      }
      if ((sourceExhausted && nextCursor != null) || (!sourceExhausted && nextCursor == null)) {
        throw new IllegalArgumentException("history window cursor state is inconsistent");
      }
    }
  }

  public record WindowFinding(
      String answer,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      List<String> referencedParticipants) {
    public WindowFinding {
      requireNotBlank(answer, "finding answer");
      Objects.requireNonNull(confidence, "finding confidence");
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
      referencedParticipants = List.copyOf(referencedParticipants);
      if (evidenceMessageGuids.isEmpty()) {
        throw new IllegalArgumentException("finding evidence must not be empty");
      }
    }
  }

  public record ModelWindowDecision(
      WindowAction action,
      @Nullable String answer,
      @Nullable String clarificationQuestion,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      List<WindowFinding> provisionalFindings,
      List<String> referencedParticipants) {
    public ModelWindowDecision {
      Objects.requireNonNull(action, "window action");
      answer = StringUtils.trimToNull(answer);
      clarificationQuestion = StringUtils.trimToNull(clarificationQuestion);
      Objects.requireNonNull(confidence, "window confidence");
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
      provisionalFindings = List.copyOf(provisionalFindings);
      referencedParticipants = List.copyOf(referencedParticipants);
      switch (action) {
        case ANSWERED -> {
          if (answer == null
              || clarificationQuestion != null
              || evidenceMessageGuids.isEmpty()
              || !provisionalFindings.isEmpty()) {
            throw new IllegalArgumentException("answered window decision has invalid shape");
          }
        }
        case NEED_OLDER_MESSAGES -> {
          if (answer != null
              || clarificationQuestion != null
              || !evidenceMessageGuids.isEmpty()
              || !referencedParticipants.isEmpty()) {
            throw new IllegalArgumentException("older-window decision has invalid shape");
          }
        }
        case NEED_TIME_CLARIFICATION -> {
          if (answer != null
              || clarificationQuestion == null
              || !evidenceMessageGuids.isEmpty()
              || !provisionalFindings.isEmpty()
              || !referencedParticipants.isEmpty()) {
            throw new IllegalArgumentException("clarification decision has invalid shape");
          }
        }
        case NO_ANSWER -> {
          if (answer == null
              || clarificationQuestion != null
              || !evidenceMessageGuids.isEmpty()
              || !provisionalFindings.isEmpty()
              || !referencedParticipants.isEmpty()) {
            throw new IllegalArgumentException("no-answer decision has invalid shape");
          }
        }
      }
    }
  }

  public record RoutedWindowDecision(
      ModelWindowDecision decision, String model, boolean fallbackUsed) {
    public RoutedWindowDecision {
      Objects.requireNonNull(decision, "window decision");
      requireNotBlank(model, "window decision model");
    }
  }

  public record RoutedFindingReduction(
      ModelWindowDecision decision,
      List<QuestionFinding> citedFindings,
      String model,
      boolean fallbackUsed) {
    public RoutedFindingReduction {
      Objects.requireNonNull(decision, "finding reduction decision");
      citedFindings = List.copyOf(citedFindings);
      requireNotBlank(model, "finding reduction model");
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
      Instant deadline,
      ConversationHistoryMessageMapper.MappingSession mappingSession) {
    public RetrievalRequest(
        String accountId,
        ConversationRecord conversation,
        List<MembershipInterval> memberships,
        Instant from,
        Instant to,
        Instant deadline) {
      this(
          accountId,
          conversation,
          memberships,
          from,
          to,
          deadline,
          new ConversationHistoryMessageMapper.MappingSession());
    }

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
      if (mappingSession == null) {
        throw new IllegalArgumentException("mapping session must not be null");
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

  public record RoutedReductionAnswer(
      RoutedModelAnswer routed, List<QuestionFinding> citedFindings) {
    public RoutedReductionAnswer {
      if (routed == null) {
        throw new IllegalArgumentException("routed reduction answer must not be null");
      }
      citedFindings = List.copyOf(citedFindings);
    }
  }

  public record RoutedSupportVerification(boolean supported, String model, boolean fallbackUsed) {
    public RoutedSupportVerification {
      requireNotBlank(model, "verification model");
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

  public static final class QuestionFinding {
    private final String answer;
    private final Confidence confidence;
    private final List<String> evidenceMessageGuids;
    private final Instant coverageThrough;
    private final List<String> referencedParticipants;

    public QuestionFinding(
        String answer,
        Confidence confidence,
        List<String> evidenceMessageGuids,
        Instant coverageThrough) {
      this(answer, confidence, evidenceMessageGuids, coverageThrough, List.of());
    }

    public QuestionFinding(
        String answer,
        Confidence confidence,
        List<String> evidenceMessageGuids,
        Instant coverageThrough,
        List<String> referencedParticipants) {
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      if (coverageThrough == null) {
        throw new IllegalArgumentException("coverage through must not be null");
      }
      this.answer = answer;
      this.confidence = confidence;
      this.evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
      this.coverageThrough = coverageThrough;
      this.referencedParticipants = List.copyOf(referencedParticipants);
    }

    public String answer() {
      return answer;
    }

    public Confidence confidence() {
      return confidence;
    }

    public List<String> evidenceMessageGuids() {
      return evidenceMessageGuids;
    }

    public Instant coverageThrough() {
      return coverageThrough;
    }

    public List<String> referencedParticipants() {
      return referencedParticipants;
    }
  }

  private static void requireNotBlank(String value, String name) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
