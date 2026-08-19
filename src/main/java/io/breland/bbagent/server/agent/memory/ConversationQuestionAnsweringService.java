package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindow;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindowCursor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.MembershipInterval;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ModelWindowDecision;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantHint;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionFinding;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedFindingReduction;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedWindowDecision;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.WindowAction;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.WindowFinding;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConversationQuestionAnsweringService {
  private static final int HARD_MAX_WINDOW_MESSAGES = 500;
  private static final int HARD_MAX_HISTORY_PAGES = 100;
  private static final int HARD_MAX_BATCH_CHARACTERS = 300_000;
  private static final int HARD_MAX_MODEL_BATCHES = 5;
  private static final int HARD_MAX_AGGREGATE_CHARACTERS = 1_500_000;
  private static final Duration HARD_MAX_REQUEST_TIMEOUT = Duration.ofSeconds(90);

  private static final String EMPTY_HISTORY_ANSWER =
      "I couldn't find any group messages in that time range.";
  private static final String NO_ANSWER = "I couldn't find that in this group's messages.";
  private static final String UNAVAILABLE_ANSWER =
      "I couldn't search the group history right now. Please try again.";
  private static final String NARROW_TIME_QUESTION = "About when should I look?";
  private static final String SOURCE_UNAVAILABLE = "source_unavailable";
  private static final String TIME_LIMIT = "time_limit";
  private static final String MODEL_LIMIT = "model_limit";

  private final ConversationMemoryStore store;
  private final ConversationQuestionHistoryRetriever retriever;
  private final ConversationQuestionAnsweringModelClient model;
  private final OperationalMetricsService metrics;
  private final int windowMessageCount;
  private final int maxHistoryPages;
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
      OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.qa.window-message-count}") int windowMessageCount,
      @Value("${bbagent.memory.group.qa.max-history-pages}") int maxHistoryPages,
      @Value("${bbagent.memory.group.qa.max-batch-characters}") int maxBatchCharacters,
      @Value("${bbagent.memory.group.qa.max-model-batches}") int maxModelBatches,
      @Value("${bbagent.memory.group.qa.max-aggregate-characters}") int maxAggregateCharacters,
      @Value("${bbagent.memory.group.qa.request-timeout}") Duration requestTimeout) {
    this(
        store,
        retriever,
        model,
        metrics,
        windowMessageCount,
        maxHistoryPages,
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
      OperationalMetricsService metrics,
      int windowMessageCount,
      int maxHistoryPages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters,
      Duration requestTimeout,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.retriever = Objects.requireNonNull(retriever, "retriever");
    this.model = Objects.requireNonNull(model, "model");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    if (windowMessageCount < 1 || windowMessageCount > HARD_MAX_WINDOW_MESSAGES) {
      throw new IllegalArgumentException("window message count must be between 1 and 500");
    }
    if (maxHistoryPages < 1 || maxHistoryPages > HARD_MAX_HISTORY_PAGES) {
      throw new IllegalArgumentException("max history pages must be between 1 and 100");
    }
    if (maxBatchCharacters < 1 || maxBatchCharacters > HARD_MAX_BATCH_CHARACTERS) {
      throw new IllegalArgumentException("max batch characters must be between 1 and 300000");
    }
    if (maxModelBatches < 1 || maxModelBatches > HARD_MAX_MODEL_BATCHES) {
      throw new IllegalArgumentException("max model batches must be between 1 and 5");
    }
    if (maxAggregateCharacters < maxBatchCharacters
        || maxAggregateCharacters > HARD_MAX_AGGREGATE_CHARACTERS) {
      throw new IllegalArgumentException(
          "max aggregate characters must be between max batch characters and 1500000");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(HARD_MAX_REQUEST_TIMEOUT) > 0) {
      throw new IllegalArgumentException("request timeout must be between zero and 90 seconds");
    }
    this.windowMessageCount = windowMessageCount;
    this.maxHistoryPages = maxHistoryPages;
    this.maxBatchCharacters = maxBatchCharacters;
    this.maxModelBatches = maxModelBatches;
    this.maxAggregateCharacters = maxAggregateCharacters;
    this.requestTimeout = requestTimeout;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public GroupQuestionAnswer answer(
      String accountId, AuthorizedGroup group, String question, Instant from, Instant to) {
    return answer(accountId, group, question, from, to, null);
  }

  public GroupQuestionAnswer answer(
      String accountId,
      AuthorizedGroup group,
      String question,
      @Nullable Instant from,
      Instant to,
      @Nullable String timezone) {
    requireRequest(accountId, group, question, from, to, timezone);
    Instant startedAt = clock.instant();
    Instant effectiveFrom = from == null ? Instant.EPOCH : from;
    Instant deadline = startedAt.plus(requestTimeout);
    RunState run = new RunState();
    GroupQuestionAnswer result;
    try {
      result =
          answerProgressively(
              accountId, group, question, effectiveFrom, to, timezone, startedAt, deadline, run);
    } catch (RuntimeException failure) {
      log.warn(
          "Group question answering failed failureType={} detail={} messages={} pages={} windows={} modelCalls={}",
          OperationalMetricsService.failureType(failure),
          ConversationMemoryResponsesClient.safeFailureDetail(failure),
          run.messagesByGuid.size(),
          run.pageCount,
          run.windowCount,
          run.modelCalls);
      result = unavailable(effectiveFrom, to, run, SOURCE_UNAVAILABLE);
    }
    recordMetrics(result, run, startedAt);
    return result;
  }

  private GroupQuestionAnswer answerProgressively(
      String accountId,
      AuthorizedGroup group,
      String question,
      Instant from,
      Instant to,
      @Nullable String timezone,
      Instant referenceTime,
      Instant deadline,
      RunState run) {
    Optional<ConversationRecord> conversationValue = store.findConversation(group.conversationId());
    if (conversationValue.isEmpty() || !enabledGroup(conversationValue.get())) {
      return unavailable(from, to, run, SOURCE_UNAVAILABLE);
    }
    ConversationRecord conversation = conversationValue.get();
    List<MembershipInterval> memberships =
        store.findMembershipIntervals(group.conversationId(), accountId, from, to);
    if (memberships.isEmpty()) {
      return unavailable(from, to, run, SOURCE_UNAVAILABLE);
    }
    if (accountId.equals(conversation.memoryEnabledByAccountId())
        && memberships.stream().anyMatch(interval -> interval.endedAt() == null)) {
      memberships = List.of(new MembershipInterval(Instant.EPOCH, null));
    }

    RetrievalRequest request =
        new RetrievalRequest(accountId, conversation, memberships, from, to, deadline);
    HistoryWindowCursor cursor = null;
    Set<HistoryWindowCursor> seenCursors = new LinkedHashSet<>();
    while (true) {
      if (deadlineReached(deadline)) {
        return unavailable(from, to, run, TIME_LIMIT);
      }
      if (run.pageCount >= maxHistoryPages) {
        return clarification(from, to, run, NARROW_TIME_QUESTION, null, false);
      }

      HistoryWindow window = retriever.retrieveWindow(request, cursor, windowMessageCount);
      run.observe(window);
      if (run.pageCount > maxHistoryPages) {
        return clarification(from, to, run, NARROW_TIME_QUESTION, null, false);
      }
      if (window.messages().isEmpty()) {
        if (window.sourceExhausted()) {
          if (run.findings.isEmpty()) {
            return noAnswer(from, to, run, EMPTY_HISTORY_ANSWER, null, false);
          }
          return Objects.requireNonNull(
              reduceFindings(question, referenceTime, timezone, false, deadline, from, to, run));
        }
        cursor = advanceCursor(window, seenCursors);
        continue;
      }

      List<List<QuestionMessage>> chunks =
          chunkMessages(question, referenceTime, timezone, window.messages());
      if (chunks == null) {
        return clarification(from, to, run, NARROW_TIME_QUESTION, null, false);
      }
      if (chunks.size() > 1 && run.modelCalls + chunks.size() + 1 > maxModelBatches) {
        return clarification(from, to, run, NARROW_TIME_QUESTION, null, false);
      }

      List<ModelWindowDecision> decisions = new ArrayList<>(chunks.size());
      for (List<QuestionMessage> chunk : chunks) {
        RoutedWindowDecision routed =
            decide(question, referenceTime, timezone, chunk, deadline, run);
        if (routed == null) {
          return unavailable(from, to, run, deadlineReached(deadline) ? TIME_LIMIT : MODEL_LIMIT);
        }
        validateDecision(routed.decision(), chunk);
        run.observe(routed);
        decisions.add(routed.decision());
      }

      if (chunks.size() == 1 && run.findings.isEmpty()) {
        ModelWindowDecision decision = decisions.getFirst();
        switch (decision.action()) {
          case ANSWERED -> {
            return answered(from, to, run, decision, run.model, run.fallbackUsed);
          }
          case NEED_TIME_CLARIFICATION -> {
            return clarification(
                from, to, run, decision.clarificationQuestion(), run.model, run.fallbackUsed);
          }
          case NO_ANSWER -> {
            if (window.nextCursor() == null) {
              return noAnswer(from, to, run, decision.answer(), run.model, run.fallbackUsed);
            }
          }
          case NEED_OLDER_MESSAGES -> addProvisionalFindings(decision, run);
        }
      } else {
        boolean needsOlder = false;
        String noAnswerText = null;
        for (ModelWindowDecision decision : decisions) {
          switch (decision.action()) {
            case ANSWERED -> addAnsweredFinding(decision, run);
            case NEED_OLDER_MESSAGES -> {
              addProvisionalFindings(decision, run);
              needsOlder = true;
            }
            case NEED_TIME_CLARIFICATION -> {
              return clarification(
                  from, to, run, decision.clarificationQuestion(), run.model, run.fallbackUsed);
            }
            case NO_ANSWER -> {
              noAnswerText = decision.answer();
            }
          }
        }
        boolean olderAvailable = window.nextCursor() != null;
        if (!run.findings.isEmpty()) {
          GroupQuestionAnswer reduced =
              reduceFindings(
                  question, referenceTime, timezone, olderAvailable, deadline, from, to, run);
          if (reduced != null) {
            return reduced;
          }
          needsOlder = true;
        } else if (!needsOlder && !olderAvailable) {
          return noAnswer(
              from,
              to,
              run,
              StringUtils.defaultIfBlank(noAnswerText, NO_ANSWER),
              run.model,
              run.fallbackUsed);
        }
      }

      if (window.nextCursor() == null) {
        if (run.findings.isEmpty()) {
          return noAnswer(from, to, run, NO_ANSWER, run.model, run.fallbackUsed);
        }
        return Objects.requireNonNull(
            reduceFindings(question, referenceTime, timezone, false, deadline, from, to, run));
      }
      cursor = advanceCursor(window, seenCursors);
    }
  }

  private @Nullable RoutedWindowDecision decide(
      String question,
      Instant referenceTime,
      @Nullable String timezone,
      List<QuestionMessage> messages,
      Instant deadline,
      RunState run) {
    int characters = model.windowInputCharacters(question, referenceTime, timezone, messages);
    if (!run.reserveModelCall(characters)) {
      return null;
    }
    RoutedWindowDecision routed =
        model.decide(question, referenceTime, timezone, messages, deadline);
    return deadlineReached(deadline) ? null : routed;
  }

  private @Nullable GroupQuestionAnswer reduceFindings(
      String question,
      Instant referenceTime,
      @Nullable String timezone,
      boolean olderAvailable,
      Instant deadline,
      Instant from,
      Instant to,
      RunState run) {
    if (run.findings.isEmpty()) {
      return noAnswer(from, to, run, NO_ANSWER, run.model, run.fallbackUsed);
    }
    run.findings.sort(Comparator.comparing(QuestionFinding::coverageThrough));
    int characters =
        model.findingReductionInputCharacters(
            question, referenceTime, timezone, run.findings, olderAvailable);
    if (!run.reserveModelCall(characters)) {
      return unavailable(from, to, run, deadlineReached(deadline) ? TIME_LIMIT : MODEL_LIMIT);
    }
    RoutedFindingReduction routed =
        model.reduceFindings(
            question, referenceTime, timezone, List.copyOf(run.findings), olderAvailable, deadline);
    if (deadlineReached(deadline)) {
      return unavailable(from, to, run, TIME_LIMIT);
    }
    validateReduction(routed, run.findings);
    run.observe(routed);
    ModelWindowDecision decision = routed.decision();
    return switch (decision.action()) {
      case ANSWERED -> answered(from, to, run, decision, run.model, run.fallbackUsed);
      case NEED_TIME_CLARIFICATION ->
          clarification(
              from, to, run, decision.clarificationQuestion(), run.model, run.fallbackUsed);
      case NO_ANSWER ->
          olderAvailable
              ? null
              : noAnswer(from, to, run, decision.answer(), run.model, run.fallbackUsed);
      case NEED_OLDER_MESSAGES ->
          olderAvailable
              ? null
              : clarification(from, to, run, NARROW_TIME_QUESTION, run.model, run.fallbackUsed);
    };
  }

  private List<List<QuestionMessage>> chunkMessages(
      String question,
      Instant referenceTime,
      @Nullable String timezone,
      List<QuestionMessage> messages) {
    List<List<QuestionMessage>> chunks = new ArrayList<>();
    List<QuestionMessage> current = new ArrayList<>();
    for (QuestionMessage message : messages) {
      List<QuestionMessage> candidate = new ArrayList<>(current);
      candidate.add(message);
      if (model.windowInputCharacters(question, referenceTime, timezone, candidate)
          <= maxBatchCharacters) {
        current = candidate;
        continue;
      }
      if (current.isEmpty()) {
        return null;
      }
      chunks.add(List.copyOf(current));
      current = new ArrayList<>(List.of(message));
      if (model.windowInputCharacters(question, referenceTime, timezone, current)
          > maxBatchCharacters) {
        return null;
      }
    }
    if (!current.isEmpty()) {
      chunks.add(List.copyOf(current));
    }
    return List.copyOf(chunks);
  }

  private static void validateDecision(
      ModelWindowDecision decision, List<QuestionMessage> submittedMessages) {
    Map<String, QuestionMessage> submitted = new LinkedHashMap<>();
    submittedMessages.forEach(message -> submitted.put(message.messageGuid(), message));
    if (decision.action() == WindowAction.ANSWERED) {
      validateEvidenceAndParticipants(
          decision.evidenceMessageGuids(), decision.referencedParticipants(), submitted);
    }
    for (WindowFinding finding : decision.provisionalFindings()) {
      validateEvidenceAndParticipants(
          finding.evidenceMessageGuids(), finding.referencedParticipants(), submitted);
    }
  }

  private static void validateReduction(
      RoutedFindingReduction routed, List<QuestionFinding> submittedFindings) {
    Set<QuestionFinding> submitted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    submitted.addAll(submittedFindings);
    if (!submitted.containsAll(routed.citedFindings())) {
      throw new IllegalStateException("finding reduction cited an unsubmitted finding");
    }
    LinkedHashSet<String> evidence = new LinkedHashSet<>();
    LinkedHashSet<String> participants = new LinkedHashSet<>();
    routed
        .citedFindings()
        .forEach(
            finding -> {
              evidence.addAll(finding.evidenceMessageGuids());
              participants.addAll(finding.referencedParticipants());
            });
    ModelWindowDecision decision = routed.decision();
    if (decision.action() == WindowAction.ANSWERED
        && (!evidence.equals(new LinkedHashSet<>(decision.evidenceMessageGuids()))
            || !participants.containsAll(decision.referencedParticipants()))) {
      throw new IllegalStateException("finding reduction evidence is inconsistent");
    }
  }

  private static void validateEvidenceAndParticipants(
      List<String> evidenceGuids,
      List<String> referencedParticipants,
      Map<String, QuestionMessage> submitted) {
    LinkedHashSet<String> participants = new LinkedHashSet<>();
    for (String guid : evidenceGuids) {
      QuestionMessage message = submitted.get(guid);
      if (message == null) {
        throw new IllegalStateException("question answer evidence is outside submitted messages");
      }
      participants.add(message.participant());
    }
    if (!participants.containsAll(referencedParticipants)) {
      throw new IllegalStateException("question answer participant is outside cited messages");
    }
  }

  private static void addAnsweredFinding(ModelWindowDecision decision, RunState run) {
    run.addFinding(
        new QuestionFinding(
            decision.answer(),
            decision.confidence(),
            decision.evidenceMessageGuids(),
            run.coverageThrough(decision.evidenceMessageGuids()),
            decision.referencedParticipants()));
  }

  private static void addProvisionalFindings(ModelWindowDecision decision, RunState run) {
    for (WindowFinding finding : decision.provisionalFindings()) {
      run.addFinding(
          new QuestionFinding(
              finding.answer(),
              finding.confidence(),
              finding.evidenceMessageGuids(),
              run.coverageThrough(finding.evidenceMessageGuids()),
              finding.referencedParticipants()));
    }
  }

  private GroupQuestionAnswer answered(
      Instant from,
      Instant to,
      RunState run,
      ModelWindowDecision decision,
      String modelName,
      boolean fallbackUsed) {
    if (!ConversationQuestionAnswerOutputValidator.isSafe(
        decision.answer(), run.messagesByGuid.keySet(), Set.of())) {
      return unavailable(from, to, run, "invalid_model_output");
    }
    return new GroupQuestionAnswer(
        AnswerStatus.ANSWERED,
        decision.answer(),
        null,
        run.hints(decision.evidenceMessageGuids(), decision.referencedParticipants()),
        modelName,
        fallbackUsed);
  }

  private GroupQuestionAnswer noAnswer(
      Instant from,
      Instant to,
      RunState run,
      String answer,
      @Nullable String modelName,
      boolean fallbackUsed) {
    if (!ConversationQuestionAnswerOutputValidator.isSafe(
        answer, run.messagesByGuid.keySet(), Set.of())) {
      return unavailable(from, to, run, "invalid_model_output");
    }
    return new GroupQuestionAnswer(
        AnswerStatus.NO_ANSWER, answer, null, List.of(), modelName, fallbackUsed);
  }

  private GroupQuestionAnswer clarification(
      Instant from,
      Instant to,
      RunState run,
      String clarificationQuestion,
      @Nullable String modelName,
      boolean fallbackUsed) {
    if (!ConversationQuestionAnswerOutputValidator.isSafe(
        clarificationQuestion, run.messagesByGuid.keySet(), Set.of())) {
      return unavailable(from, to, run, "invalid_model_output");
    }
    return new GroupQuestionAnswer(
        AnswerStatus.CLARIFICATION_REQUIRED,
        null,
        clarificationQuestion,
        List.of(),
        modelName,
        fallbackUsed);
  }

  private GroupQuestionAnswer unavailable(Instant from, Instant to, RunState run, String reason) {
    run.partialReason = StringUtils.defaultIfBlank(run.partialReason, reason);
    return new GroupQuestionAnswer(
        AnswerStatus.UNAVAILABLE, UNAVAILABLE_ANSWER, null, List.of(), run.model, run.fallbackUsed);
  }

  private void recordMetrics(GroupQuestionAnswer result, RunState run, Instant startedAt) {
    boolean success = result.status() != AnswerStatus.UNAVAILABLE;
    metrics.recordMemoryQuestionAnswer(
        result.status().wireValue(),
        result.model(),
        run.messagesByGuid.size(),
        run.pageCount,
        run.windowCount,
        run.modelCalls,
        run.reductionCount,
        success,
        success ? null : result.status().wireValue(),
        Duration.between(startedAt, clock.instant()));
  }

  private static HistoryWindowCursor advanceCursor(
      HistoryWindow window, Set<HistoryWindowCursor> seenCursors) {
    HistoryWindowCursor next = Objects.requireNonNull(window.nextCursor(), "next history cursor");
    if (!seenCursors.add(next)) {
      throw new IllegalStateException("history cursor did not advance");
    }
    return next;
  }

  private static void requireRequest(
      String accountId,
      AuthorizedGroup group,
      String question,
      @Nullable Instant from,
      Instant to,
      @Nullable String timezone) {
    if (StringUtils.isBlank(accountId)) {
      throw new IllegalArgumentException("account id must not be blank");
    }
    Objects.requireNonNull(group, "group");
    if (StringUtils.isBlank(question)
        || question.length() > ConversationQuestionAnsweringModelClient.MAX_QUESTION_LENGTH) {
      throw new IllegalArgumentException("question is invalid");
    }
    if (to == null || (from != null && !from.isBefore(to))) {
      throw new IllegalArgumentException("question range must be ordered");
    }
    if (StringUtils.isNotBlank(timezone)) {
      try {
        ZoneId.of(timezone.trim());
      } catch (DateTimeException e) {
        throw new IllegalArgumentException("timezone is invalid", e);
      }
    }
  }

  private boolean deadlineReached(Instant deadline) {
    return !clock.instant().isBefore(deadline);
  }

  private static boolean enabledGroup(ConversationRecord conversation) {
    return conversation.group() && conversation.memoryEnabledAt() != null;
  }

  private final class RunState {
    private final LinkedHashMap<String, QuestionMessage> messagesByGuid = new LinkedHashMap<>();
    private final List<QuestionFinding> findings = new ArrayList<>();
    private final Set<String> findingKeys = new LinkedHashSet<>();
    private int pageCount;
    private int windowCount;
    private int modelCalls;
    private int reductionCount;
    private int aggregateCharacters;
    private @Nullable String model;
    private boolean fallbackUsed;
    private @Nullable String partialReason;

    private void observe(HistoryWindow window) {
      windowCount = Math.addExact(windowCount, 1);
      pageCount = Math.addExact(pageCount, window.pageCount());
      if (!window.windowComplete()) {
        partialReason = StringUtils.defaultIfBlank(partialReason, window.partialReason());
      }
      for (QuestionMessage message : window.messages()) {
        QuestionMessage previous = messagesByGuid.putIfAbsent(message.messageGuid(), message);
        if (previous != null && !previous.equals(message)) {
          throw new IllegalStateException("history returned conflicting messages");
        }
      }
    }

    private void observe(RoutedWindowDecision routed) {
      model = routed.model();
      fallbackUsed |= routed.fallbackUsed();
    }

    private void observe(RoutedFindingReduction routed) {
      reductionCount = Math.addExact(reductionCount, 1);
      model = routed.model();
      fallbackUsed |= routed.fallbackUsed();
    }

    private boolean reserveModelCall(int characters) {
      if (characters < 1
          || characters > maxBatchCharacters
          || modelCalls >= maxModelBatches
          || aggregateCharacters > maxAggregateCharacters - characters) {
        return false;
      }
      modelCalls++;
      aggregateCharacters += characters;
      return true;
    }

    private void addFinding(QuestionFinding finding) {
      String key =
          finding.answer() + '\u0000' + String.join("\u0000", finding.evidenceMessageGuids());
      if (findingKeys.add(key)) {
        findings.add(finding);
      }
    }

    private Instant coverageThrough(List<String> evidenceGuids) {
      return java.util.stream.Stream.concat(
              evidenceGuids.stream().map(messagesByGuid::get).filter(Objects::nonNull),
              evidenceGuids.isEmpty()
                  ? messagesByGuid.values().stream()
                  : java.util.stream.Stream.empty())
          .map(QuestionMessage::timestamp)
          .max(Comparator.naturalOrder())
          .orElseThrow(() -> new IllegalStateException("finding has no submitted messages"));
    }

    private List<ParticipantHint> hints(
        List<String> evidenceGuids, List<String> referencedParticipants) {
      Set<String> labels = new LinkedHashSet<>(referencedParticipants);
      LinkedHashMap<String, ParticipantHint> hints = new LinkedHashMap<>();
      for (String guid : evidenceGuids) {
        QuestionMessage message = messagesByGuid.get(guid);
        if (message == null || !labels.contains(message.participant())) {
          continue;
        }
        ParticipantHint hint = message.participantHint();
        if (hint != null && hint.label().equals(message.participant())) {
          hints.putIfAbsent(hint.normalizedIdentity(), hint);
        }
      }
      return List.copyOf(hints.values());
    }
  }
}
