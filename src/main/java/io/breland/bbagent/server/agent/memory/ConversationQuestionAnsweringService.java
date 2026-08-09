package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.Confidence;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.CoverageStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.MembershipInterval;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ModelAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionFinding;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalMode;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedModelAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationQuestionAnsweringService {
  private static final int HARD_MAX_BATCH_MESSAGES = 100;
  private static final int HARD_MAX_BATCH_CHARACTERS = 60_000;
  private static final int HARD_MAX_MODEL_BATCHES = 5;
  private static final int HARD_MAX_AGGREGATE_CHARACTERS = 300_000;
  private static final Duration HARD_MAX_REQUEST_TIMEOUT = Duration.ofSeconds(90);

  private static final String INSUFFICIENT_ANSWER =
      "There is insufficient evidence in the authorized group history to answer that question.";
  private static final String UNAVAILABLE_ANSWER =
      "Group history answering is temporarily unavailable.";
  private static final String UNAUTHORIZED_RANGE = "unauthorized_range";
  private static final String SOURCE_UNAVAILABLE = "source_unavailable";
  private static final String MODEL_UNAVAILABLE = "model_unavailable";
  private static final String MODEL_INVALID = "model_invalid";
  private static final String TIME_LIMIT = "time_limit";
  private static final String MODEL_BATCH_LIMIT = "model_batch_limit";
  private static final String CHARACTER_LIMIT = "character_limit";
  private static final String NEEDS_MORE_CONTEXT = "needs_more_context";

  private final ConversationMemoryStore store;
  private final ConversationQuestionHistoryRetriever retriever;
  private final ConversationQuestionAnsweringModelClient model;
  private final int maxBatchMessages;
  private final int maxBatchCharacters;
  private final int maxModelBatches;
  private final int maxAggregateCharacters;
  private final Duration requestTimeout;
  private final Clock clock;

  @Autowired
  public ConversationQuestionAnsweringService(
      ConversationMemoryStore store,
      ConversationQuestionHistoryRetriever retriever,
      ConversationQuestionAnsweringModelClient model,
      @Value("${bbagent.memory.group.qa.max-batch-messages:100}") int maxBatchMessages,
      @Value("${bbagent.memory.group.qa.max-batch-characters:60000}") int maxBatchCharacters,
      @Value("${bbagent.memory.group.qa.max-model-batches:5}") int maxModelBatches,
      @Value("${bbagent.memory.group.qa.max-aggregate-characters:300000}")
          int maxAggregateCharacters,
      @Value("${bbagent.memory.group.qa.request-timeout:PT90S}") Duration requestTimeout) {
    this(
        store,
        retriever,
        model,
        maxBatchMessages,
        maxBatchCharacters,
        maxModelBatches,
        maxAggregateCharacters,
        requestTimeout,
        Clock.systemUTC());
  }

  ConversationQuestionAnsweringService(
      ConversationMemoryStore store,
      ConversationQuestionHistoryRetriever retriever,
      ConversationQuestionAnsweringModelClient model,
      int maxBatchMessages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters,
      Duration requestTimeout,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.retriever = Objects.requireNonNull(retriever, "retriever");
    this.model = Objects.requireNonNull(model, "model");
    if (maxBatchMessages <= 0 || maxBatchMessages > HARD_MAX_BATCH_MESSAGES) {
      throw new IllegalArgumentException("max batch messages must be between 1 and 100");
    }
    if (maxBatchCharacters <= 0 || maxBatchCharacters > HARD_MAX_BATCH_CHARACTERS) {
      throw new IllegalArgumentException("max batch characters must be between 1 and 60000");
    }
    if (maxModelBatches <= 0 || maxModelBatches > HARD_MAX_MODEL_BATCHES) {
      throw new IllegalArgumentException("max model batches must be between 1 and 5");
    }
    if (maxAggregateCharacters < maxBatchCharacters
        || maxAggregateCharacters > HARD_MAX_AGGREGATE_CHARACTERS) {
      throw new IllegalArgumentException(
          "max aggregate characters must be between max batch characters and 300000");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(HARD_MAX_REQUEST_TIMEOUT) > 0) {
      throw new IllegalArgumentException("request timeout must be between zero and 90 seconds");
    }
    this.maxBatchMessages = maxBatchMessages;
    this.maxBatchCharacters = maxBatchCharacters;
    this.maxModelBatches = maxModelBatches;
    this.maxAggregateCharacters = maxAggregateCharacters;
    this.requestTimeout = requestTimeout;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public GroupQuestionAnswer answer(
      String accountId, AuthorizedGroup group, String question, Instant from, Instant to) {
    requireRequest(accountId, group, question, from, to);
    Instant deadline = clock.instant().plus(requestTimeout);
    ModelBudget budget = new ModelBudget();
    try {
      Optional<ConversationRecord> conversationValue =
          store.findConversation(group.conversationId());
      if (conversationValue.isEmpty() || !enabledGroup(conversationValue.get())) {
        return insufficient(from, to, RetrievalMode.CHRONOLOGICAL, UNAUTHORIZED_RANGE, from);
      }
      ConversationRecord conversation = conversationValue.get();
      List<MembershipInterval> memberships =
          store.findMembershipIntervals(group.conversationId(), accountId, from, to);
      if (memberships.isEmpty()) {
        return insufficient(from, to, RetrievalMode.CHRONOLOGICAL, UNAUTHORIZED_RANGE, from);
      }

      RetrievalRequest request =
          new RetrievalRequest(accountId, conversation, memberships, from, to, deadline);
      SearchPlan plan = safePlan(question, from, to, deadline);
      boolean exactSourceAttempted = !plan.terms().isEmpty();
      RetrievalResult exact = null;
      Synthesis exactSynthesis = null;
      try {
        if (deadlineReached(deadline)) {
          return unavailable(from, to, RetrievalMode.EXACT_SEARCH, TIME_LIMIT, from);
        }
        exact = retriever.retrieveExact(request, plan);
        if (!exact.messages().isEmpty() && !deadlineReached(deadline)) {
          exactSynthesis = synthesize(question, exact.messages(), from, deadline, budget);
          if (exactSynthesis.supported() && !exactSynthesis.routed().answer().needsMoreContext()) {
            return finalAnswer(exactSynthesis, exact, RetrievalMode.EXACT_SEARCH, from, to, null);
          }
        }
      } catch (RuntimeException ignored) {
        exactSynthesis = Synthesis.unavailable(from, SOURCE_UNAVAILABLE);
      }

      RetrievalMode fallbackMode =
          exactSourceAttempted ? RetrievalMode.HYBRID : RetrievalMode.CHRONOLOGICAL;
      if (deadlineReached(deadline)) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, TIME_LIMIT);
      }

      RetrievalResult chronological;
      try {
        chronological = retriever.retrieveChronological(request);
      } catch (RuntimeException ignored) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, SOURCE_UNAVAILABLE);
      }
      if (deadlineReached(deadline) && chronological.messages().isEmpty()) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, TIME_LIMIT);
      }

      Synthesis chronologicalSynthesis =
          synthesize(question, chronological.messages(), from, deadline, budget);
      if (chronologicalSynthesis.supported()) {
        return finalAnswer(chronologicalSynthesis, chronological, fallbackMode, from, to, null);
      }
      if (exactSynthesis != null && exactSynthesis.supported()) {
        String reason =
            firstReason(
                chronologicalSynthesis.partialReason(),
                chronological.partialReason(),
                NEEDS_MORE_CONTEXT);
        return finalAnswer(exactSynthesis, exact, fallbackMode, from, to, reason);
      }
      return unsupportedAnswer(chronologicalSynthesis, chronological, fallbackMode, from, to);
    } catch (RuntimeException ignored) {
      return unavailable(from, to, RetrievalMode.CHRONOLOGICAL, SOURCE_UNAVAILABLE, from);
    }
  }

  private SearchPlan safePlan(String question, Instant from, Instant to, Instant deadline) {
    if (deadlineReached(deadline)) {
      return emptyPlan();
    }
    try {
      return Objects.requireNonNull(
          model.plan(question, from, to), "model returned no search plan");
    } catch (RuntimeException ignored) {
      return emptyPlan();
    }
  }

  private Synthesis synthesize(
      String question,
      List<QuestionMessage> submittedMessages,
      Instant from,
      Instant deadline,
      ModelBudget budget) {
    List<QuestionMessage> messages = List.copyOf(submittedMessages);
    List<SupportedFinding> findings = new ArrayList<>();
    RoutedModelAnswer lastUnsupported = null;
    Instant processedThrough = from;
    String partialReason = null;
    int nextIndex = 0;

    while (nextIndex < messages.size()) {
      if (deadlineReached(deadline)) {
        partialReason = TIME_LIMIT;
        break;
      }
      if (budget.modelBatches >= maxModelBatches) {
        partialReason = MODEL_BATCH_LIMIT;
        break;
      }
      int aggregateRemaining = maxAggregateCharacters - budget.characters;
      if (aggregateRemaining <= 0) {
        partialReason = CHARACTER_LIMIT;
        break;
      }

      Batch batch = nextBatch(messages, nextIndex, aggregateRemaining);
      if (batch.messages().isEmpty()) {
        partialReason = CHARACTER_LIMIT;
        break;
      }
      budget.modelBatches++;
      budget.characters += batch.characters();
      RoutedModelAnswer routed;
      try {
        routed = model.answer(question, batch.messages());
      } catch (RuntimeException ignored) {
        partialReason = MODEL_UNAVAILABLE;
        break;
      }
      ModelAnswer validated = validateAnswer(routed, messageGuids(batch.messages()));
      if (validated == null) {
        partialReason = MODEL_INVALID;
        break;
      }

      processedThrough = maxTimestamp(batch.messages(), processedThrough);
      nextIndex = batch.nextIndex();
      boolean completedAtDeadline = deadlineReached(deadline);
      if (validated.status() == AnswerStatus.ANSWERED) {
        QuestionFinding finding =
            new QuestionFinding(
                validated.answer(),
                validated.confidence(),
                validated.evidenceMessageGuids(),
                processedThrough);
        findings.add(new SupportedFinding(finding, routed));
      } else {
        lastUnsupported = routed;
      }
      if (completedAtDeadline) {
        partialReason = TIME_LIMIT;
        break;
      }
      if (validated.needsMoreContext()) {
        partialReason = NEEDS_MORE_CONTEXT;
        break;
      }
      if (validated.status() == AnswerStatus.UNAVAILABLE) {
        partialReason = MODEL_UNAVAILABLE;
        break;
      }
    }

    if (partialReason == null && budget.characters >= maxAggregateCharacters) {
      partialReason = CHARACTER_LIMIT;
    }
    if (partialReason == null && budget.modelBatches >= maxModelBatches) {
      partialReason = MODEL_BATCH_LIMIT;
    }
    return finishSynthesis(
        question, findings, lastUnsupported, processedThrough, partialReason, deadline);
  }

  private Synthesis finishSynthesis(
      String question,
      List<SupportedFinding> findings,
      @Nullable RoutedModelAnswer lastUnsupported,
      Instant processedThrough,
      @Nullable String partialReason,
      Instant deadline) {
    if (findings.isEmpty()) {
      if (lastUnsupported == null) {
        return new Synthesis(null, processedThrough, partialReason, false);
      }
      return new Synthesis(
          lastUnsupported,
          processedThrough,
          firstReason(
              partialReason,
              lastUnsupported.answer().status() == AnswerStatus.UNAVAILABLE
                  ? MODEL_UNAVAILABLE
                  : null),
          lastUnsupported.answer().status() == AnswerStatus.UNAVAILABLE);
    }
    if (findings.size() == 1) {
      return new Synthesis(findings.get(0).routed(), processedThrough, partialReason, false);
    }
    if (deadlineReached(deadline)) {
      return bestFinding(findings, processedThrough, firstReason(TIME_LIMIT, partialReason));
    }

    List<QuestionFinding> questionFindings =
        findings.stream().map(SupportedFinding::finding).toList();
    Set<String> submittedEvidence = findingGuids(questionFindings);
    try {
      RoutedModelAnswer reduced = model.reduce(question, questionFindings);
      ModelAnswer validated = validateAnswer(reduced, submittedEvidence);
      if (validated == null) {
        return bestFinding(findings, processedThrough, firstReason(MODEL_INVALID, partialReason));
      }
      String reason =
          dominantReason(
              partialReason,
              validated.status() == AnswerStatus.UNAVAILABLE ? MODEL_UNAVAILABLE : null,
              validated.needsMoreContext() ? NEEDS_MORE_CONTEXT : null,
              deadlineReached(deadline) ? TIME_LIMIT : null);
      return new Synthesis(
          reduced, processedThrough, reason, validated.status() == AnswerStatus.UNAVAILABLE);
    } catch (RuntimeException ignored) {
      return bestFinding(findings, processedThrough, firstReason(MODEL_UNAVAILABLE, partialReason));
    }
  }

  private Synthesis bestFinding(
      List<SupportedFinding> findings, Instant processedThrough, String partialReason) {
    SupportedFinding selected =
        findings.stream()
            .max(
                Comparator.comparingInt(
                        (SupportedFinding finding) ->
                            confidenceRank(finding.finding().confidence()))
                    .thenComparing(finding -> finding.finding().coverageThrough()))
            .orElseThrow();
    return new Synthesis(selected.routed(), processedThrough, partialReason, false);
  }

  private Batch nextBatch(
      List<QuestionMessage> messages, int startIndex, int aggregateCharactersRemaining) {
    List<QuestionMessage> batch = new ArrayList<>();
    int characters = 0;
    int nextIndex = startIndex;
    while (nextIndex < messages.size() && batch.size() < maxBatchMessages) {
      QuestionMessage message = messages.get(nextIndex);
      int messageCharacters = message.text().length();
      if (messageCharacters > maxBatchCharacters
          || messageCharacters > aggregateCharactersRemaining) {
        break;
      }
      if (characters + messageCharacters > maxBatchCharacters
          || characters + messageCharacters > aggregateCharactersRemaining) {
        break;
      }
      batch.add(message);
      characters += messageCharacters;
      nextIndex++;
    }
    return new Batch(List.copyOf(batch), characters, nextIndex);
  }

  private ModelAnswer validateAnswer(
      @Nullable RoutedModelAnswer routed, Set<String> submittedEvidence) {
    if (routed == null || routed.answer() == null) {
      return null;
    }
    ModelAnswer answer = routed.answer();
    if (answer.status() == AnswerStatus.ANSWERED
        && (answer.evidenceMessageGuids().isEmpty()
            || !submittedEvidence.containsAll(answer.evidenceMessageGuids()))) {
      return null;
    }
    if (!submittedEvidence.containsAll(answer.evidenceMessageGuids())) {
      return null;
    }
    return answer;
  }

  private GroupQuestionAnswer finalAnswer(
      Synthesis synthesis,
      @Nullable RetrievalResult retrieval,
      RetrievalMode mode,
      Instant from,
      Instant to,
      @Nullable String forcedPartialReason) {
    ModelAnswer answer = synthesis.routed().answer();
    String reason =
        dominantReason(
            forcedPartialReason,
            synthesis.partialReason(),
            retrieval == null ? null : retrieval.partialReason());
    CoverageStatus coverage = reason == null ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL;
    Instant coverageThrough =
        coverage == CoverageStatus.COMPLETE
            ? retrieval == null ? to : retrieval.coverageThrough()
            : synthesis.coverageThrough();
    return new GroupQuestionAnswer(
        answer.status(),
        answer.answer(),
        answer.confidence(),
        distinctEvidenceCount(answer.evidenceMessageGuids()),
        mode,
        coverage,
        from,
        to,
        coverageThrough,
        reason);
  }

  private GroupQuestionAnswer unsupportedAnswer(
      Synthesis synthesis,
      RetrievalResult retrieval,
      RetrievalMode mode,
      Instant from,
      Instant to) {
    String reason = dominantReason(synthesis.partialReason(), retrieval.partialReason());
    AnswerStatus status =
        synthesis.unavailable() || unavailableReason(reason)
            ? AnswerStatus.UNAVAILABLE
            : AnswerStatus.INSUFFICIENT_EVIDENCE;
    CoverageStatus coverage = reason == null ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL;
    Instant coverageThrough =
        coverage == CoverageStatus.COMPLETE
            ? retrieval.coverageThrough()
            : synthesis.coverageThrough();
    return terminalAnswer(status, from, to, mode, coverage, reason, coverageThrough);
  }

  private GroupQuestionAnswer supportedBackupOrUnavailable(
      @Nullable Synthesis backup,
      @Nullable RetrievalResult retrieval,
      RetrievalMode mode,
      Instant from,
      Instant to,
      String reason) {
    if (backup != null && backup.supported()) {
      return finalAnswer(backup, retrieval, mode, from, to, reason);
    }
    return unavailable(from, to, mode, reason, from);
  }

  private GroupQuestionAnswer insufficient(
      Instant from,
      Instant to,
      RetrievalMode mode,
      @Nullable String reason,
      Instant coverageThrough) {
    CoverageStatus coverage = reason == null ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL;
    return terminalAnswer(
        AnswerStatus.INSUFFICIENT_EVIDENCE, from, to, mode, coverage, reason, coverageThrough);
  }

  private GroupQuestionAnswer unavailable(
      Instant from, Instant to, RetrievalMode mode, String reason, Instant coverageThrough) {
    return terminalAnswer(
        AnswerStatus.UNAVAILABLE, from, to, mode, CoverageStatus.PARTIAL, reason, coverageThrough);
  }

  private GroupQuestionAnswer terminalAnswer(
      AnswerStatus status,
      Instant from,
      Instant to,
      RetrievalMode mode,
      CoverageStatus coverage,
      @Nullable String reason,
      Instant coverageThrough) {
    return new GroupQuestionAnswer(
        status,
        status == AnswerStatus.UNAVAILABLE ? UNAVAILABLE_ANSWER : INSUFFICIENT_ANSWER,
        Confidence.LOW,
        0,
        mode,
        coverage,
        from,
        to,
        coverageThrough,
        reason);
  }

  private static boolean enabledGroup(ConversationRecord conversation) {
    return conversation.group() && conversation.memoryEnabledAt() != null;
  }

  private static SearchPlan emptyPlan() {
    return new SearchPlan(List.of(), null, null, null);
  }

  private static Set<String> messageGuids(List<QuestionMessage> messages) {
    LinkedHashSet<String> guids = new LinkedHashSet<>();
    messages.forEach(message -> guids.add(message.messageGuid()));
    return guids;
  }

  private static Set<String> findingGuids(List<QuestionFinding> findings) {
    LinkedHashSet<String> guids = new LinkedHashSet<>();
    findings.forEach(finding -> guids.addAll(finding.evidenceMessageGuids()));
    return guids;
  }

  private static int distinctEvidenceCount(List<String> evidenceGuids) {
    return new LinkedHashSet<>(evidenceGuids).size();
  }

  private static Instant maxTimestamp(List<QuestionMessage> messages, Instant fallback) {
    return messages.stream()
        .map(QuestionMessage::timestamp)
        .max(Comparator.naturalOrder())
        .orElse(fallback);
  }

  private static int confidenceRank(Confidence confidence) {
    return switch (confidence) {
      case HIGH -> 3;
      case MEDIUM -> 2;
      case LOW -> 1;
    };
  }

  private static @Nullable String firstReason(@Nullable String... reasons) {
    for (String reason : reasons) {
      String normalized = StringUtils.trimToNull(reason);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static @Nullable String dominantReason(@Nullable String... reasons) {
    for (String priority :
        List.of(TIME_LIMIT, SOURCE_UNAVAILABLE, MODEL_UNAVAILABLE, MODEL_INVALID)) {
      for (String reason : reasons) {
        if (priority.equals(StringUtils.trimToNull(reason))) {
          return priority;
        }
      }
    }
    return firstReason(reasons);
  }

  private static boolean unavailableReason(@Nullable String reason) {
    return SOURCE_UNAVAILABLE.equals(reason)
        || MODEL_UNAVAILABLE.equals(reason)
        || TIME_LIMIT.equals(reason);
  }

  private boolean deadlineReached(Instant deadline) {
    return !clock.instant().isBefore(deadline);
  }

  private static void requireRequest(
      String accountId, AuthorizedGroup group, String question, Instant from, Instant to) {
    if (StringUtils.isBlank(accountId)) {
      throw new IllegalArgumentException("account id must not be blank");
    }
    Objects.requireNonNull(group, "group");
    if (StringUtils.isBlank(question)) {
      throw new IllegalArgumentException("question must not be blank");
    }
    if (from == null || to == null || !from.isBefore(to)) {
      throw new IllegalArgumentException("question range must be ordered");
    }
  }

  private final class ModelBudget {
    private int modelBatches;
    private int characters;
  }

  private record Batch(List<QuestionMessage> messages, int characters, int nextIndex) {}

  private record SupportedFinding(QuestionFinding finding, RoutedModelAnswer routed) {}

  private record Synthesis(
      @Nullable RoutedModelAnswer routed,
      Instant coverageThrough,
      @Nullable String partialReason,
      boolean unavailable) {
    private static Synthesis unavailable(Instant coverageThrough, String reason) {
      return new Synthesis(null, coverageThrough, reason, true);
    }

    private boolean supported() {
      return routed != null && routed.answer().status() == AnswerStatus.ANSWERED;
    }
  }
}
