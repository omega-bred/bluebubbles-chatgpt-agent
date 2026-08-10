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
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedReductionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedSupportVerification;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
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
  private final OperationalMetricsService metrics;
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
      OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.qa.max-batch-messages}") int maxBatchMessages,
      @Value("${bbagent.memory.group.qa.max-batch-characters}") int maxBatchCharacters,
      @Value("${bbagent.memory.group.qa.max-model-batches}") int maxModelBatches,
      @Value("${bbagent.memory.group.qa.max-aggregate-characters}") int maxAggregateCharacters,
      @Value("${bbagent.memory.group.qa.request-timeout}") Duration requestTimeout) {
    this(
        store,
        retriever,
        model,
        metrics,
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
      OperationalMetricsService metrics,
      int maxBatchMessages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters,
      Duration requestTimeout,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.retriever = Objects.requireNonNull(retriever, "retriever");
    this.model = Objects.requireNonNull(model, "model");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
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
    Instant startedAt = clock.instant();
    ModelBudget budget = new ModelBudget();
    QuestionAnswerWork work = new QuestionAnswerWork();
    GroupQuestionAnswer result =
        answerWithinLimits(accountId, group, question, from, to, budget, work);
    boolean success = result.status() == AnswerStatus.ANSWERED;
    metrics.recordMemoryQuestionAnswer(
        result.retrievalMode().name(),
        result.coverageStatus().name(),
        result.model(),
        work.messageCount(),
        work.pageCount,
        budget.modelBatches,
        budget.plannerCalls,
        budget.verificationCalls,
        success,
        success ? null : firstReason(result.partialReason(), result.status().name()),
        Duration.between(startedAt, clock.instant()));
    return result;
  }

  private GroupQuestionAnswer answerWithinLimits(
      String accountId,
      AuthorizedGroup group,
      String question,
      Instant from,
      Instant to,
      ModelBudget budget,
      QuestionAnswerWork work) {
    if (question.length() > ConversationQuestionAnsweringModelClient.MAX_QUESTION_LENGTH) {
      return insufficient(from, to, RetrievalMode.CHRONOLOGICAL, CHARACTER_LIMIT, from);
    }
    Instant deadline = clock.instant().plus(requestTimeout);
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
      SearchPlan plan = safePlan(question, from, to, deadline, budget);
      boolean exactSourceAttempted = !plan.terms().isEmpty();
      RetrievalResult exact = null;
      Synthesis exactSynthesis = null;
      try {
        if (deadlineReached(deadline)) {
          return unavailable(from, to, RetrievalMode.EXACT_SEARCH, TIME_LIMIT, from);
        }
        exact = retriever.retrieveExact(request, plan);
        work.observe(exact);
        if (!exact.messages().isEmpty() && !deadlineReached(deadline)) {
          exactSynthesis = synthesize(question, exact.messages(), from, deadline, budget, true);
          if (exactSynthesis.supported() && !exactSynthesis.routed().answer().needsMoreContext()) {
            return finalAnswer(
                exactSynthesis,
                exact,
                RetrievalMode.EXACT_SEARCH,
                from,
                to,
                null,
                work.messageGuids());
          }
        }
      } catch (ConversationQuestionHistoryRetriever.PartialRetrievalException partialFailure) {
        exact = partialFailure.partialResult();
        work.observe(exact);
        if (!exact.messages().isEmpty() && !deadlineReached(deadline)) {
          exactSynthesis = synthesize(question, exact.messages(), from, deadline, budget, true);
        } else {
          exactSynthesis = Synthesis.unavailable(from, SOURCE_UNAVAILABLE);
        }
      } catch (RuntimeException ignored) {
        exactSynthesis = Synthesis.unavailable(from, SOURCE_UNAVAILABLE);
      }

      RetrievalMode fallbackMode =
          exactSourceAttempted ? RetrievalMode.HYBRID : RetrievalMode.CHRONOLOGICAL;
      if (deadlineReached(deadline)) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, TIME_LIMIT, work.messageGuids());
      }

      RetrievalResult chronological;
      try {
        chronological = retriever.retrieveChronological(request);
        work.observe(chronological);
      } catch (ConversationQuestionHistoryRetriever.PartialRetrievalException partialFailure) {
        chronological = partialFailure.partialResult();
        work.observe(chronological);
        Synthesis partialSynthesis =
            synthesize(question, chronological.messages(), from, deadline, budget, false);
        if (partialSynthesis.supported()) {
          return finalAnswer(
              partialSynthesis,
              chronological,
              fallbackMode,
              from,
              to,
              SOURCE_UNAVAILABLE,
              work.messageGuids());
        }
        if (exactSynthesis != null && exactSynthesis.supported()) {
          return finalAnswer(
              exactSynthesis,
              exact,
              fallbackMode,
              from,
              to,
              dominantReason(
                  SOURCE_UNAVAILABLE,
                  chronological.partialReason(),
                  partialSynthesis.partialReason()),
              work.messageGuids());
        }
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, SOURCE_UNAVAILABLE, work.messageGuids());
      } catch (RuntimeException ignored) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, SOURCE_UNAVAILABLE, work.messageGuids());
      }
      if (deadlineReached(deadline) && chronological.messages().isEmpty()) {
        return supportedBackupOrUnavailable(
            exactSynthesis, exact, fallbackMode, from, to, TIME_LIMIT, work.messageGuids());
      }

      Synthesis chronologicalSynthesis =
          synthesize(question, chronological.messages(), from, deadline, budget, false);
      if (chronologicalSynthesis.supported()) {
        return finalAnswer(
            chronologicalSynthesis,
            chronological,
            fallbackMode,
            from,
            to,
            null,
            work.messageGuids());
      }
      if (exactSynthesis != null && exactSynthesis.supported()) {
        String reason =
            firstReason(
                chronologicalSynthesis.partialReason(),
                chronological.partialReason(),
                NEEDS_MORE_CONTEXT);
        return finalAnswer(
            exactSynthesis, exact, fallbackMode, from, to, reason, work.messageGuids());
      }
      return unsupportedAnswer(chronologicalSynthesis, chronological, fallbackMode, from, to);
    } catch (RuntimeException ignored) {
      return unavailable(from, to, RetrievalMode.CHRONOLOGICAL, SOURCE_UNAVAILABLE, from);
    }
  }

  private SearchPlan safePlan(
      String question, Instant from, Instant to, Instant deadline, ModelBudget budget) {
    if (deadlineReached(deadline)) {
      return emptyPlan();
    }
    try {
      budget.plannerCalls++;
      return Objects.requireNonNull(
          model.plan(question, from, to, deadline), "model returned no search plan");
    } catch (RuntimeException ignored) {
      return emptyPlan();
    }
  }

  private Synthesis synthesize(
      String question,
      List<QuestionMessage> submittedMessages,
      Instant from,
      Instant deadline,
      ModelBudget budget,
      boolean stopOnNeedsMoreContext) {
    List<QuestionMessage> messages = List.copyOf(submittedMessages);
    Set<String> forbiddenMessageGuids = messageGuids(messages);
    List<SupportedFinding> findings = new ArrayList<>();
    RoutedModelAnswer lastUnsupported = null;
    Instant processedThrough = from;
    String partialReason = null;
    boolean needsMoreContextObserved = false;
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

      Batch batch = nextBatch(question, messages, nextIndex, aggregateRemaining);
      if (batch.messages().isEmpty()) {
        partialReason = CHARACTER_LIMIT;
        break;
      }
      budget.modelBatches++;
      budget.characters += batch.characters();
      RoutedModelAnswer routed;
      try {
        routed = model.answer(question, batch.messages(), deadline);
      } catch (RuntimeException ignored) {
        partialReason = MODEL_UNAVAILABLE;
        break;
      }
      ModelAnswer validated =
          validateAnswer(routed, messageGuids(batch.messages()), forbiddenMessageGuids);
      if (validated == null) {
        partialReason = MODEL_INVALID;
        break;
      }

      RoutedModelAnswer accepted = routed;
      if (validated.status() == AnswerStatus.ANSWERED) {
        VerificationOutcome verification =
            verifyBatchAnswer(question, routed, batch.messages(), deadline, budget);
        if (verification.failureReason() != null) {
          partialReason = verification.failureReason();
          break;
        }
        accepted = Objects.requireNonNull(verification.routed());
        validated = accepted.answer();
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
        findings.add(new SupportedFinding(finding, accepted));
      } else {
        lastUnsupported = accepted;
      }
      if (completedAtDeadline) {
        partialReason = TIME_LIMIT;
        break;
      }
      if (validated.needsMoreContext()) {
        needsMoreContextObserved = true;
        if (stopOnNeedsMoreContext) {
          partialReason = NEEDS_MORE_CONTEXT;
          break;
        }
      }
      if (validated.status() == AnswerStatus.UNAVAILABLE) {
        partialReason = MODEL_UNAVAILABLE;
        break;
      }
    }

    return finishSynthesis(
        question,
        findings,
        lastUnsupported,
        processedThrough,
        partialReason,
        needsMoreContextObserved,
        deadline,
        budget,
        forbiddenMessageGuids);
  }

  private Synthesis finishSynthesis(
      String question,
      List<SupportedFinding> findings,
      @Nullable RoutedModelAnswer lastUnsupported,
      Instant processedThrough,
      @Nullable String partialReason,
      boolean needsMoreContextObserved,
      Instant deadline,
      ModelBudget budget,
      Set<String> forbiddenMessageGuids) {
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
      return bestFinding(
          findings,
          processedThrough,
          firstReason(partialReason, needsMoreContextObserved ? NEEDS_MORE_CONTEXT : null),
          forbiddenMessageGuids);
    }
    if (deadlineReached(deadline)) {
      return bestFinding(
          findings,
          processedThrough,
          firstReason(TIME_LIMIT, partialReason),
          forbiddenMessageGuids);
    }

    List<QuestionFinding> questionFindings =
        findings.stream().map(SupportedFinding::finding).toList();
    Set<String> submittedEvidence = findingGuids(questionFindings);
    int reductionCharacters;
    try {
      reductionCharacters = model.reduceInputCharacters(question, questionFindings);
    } catch (RuntimeException ignored) {
      return bestFinding(
          findings,
          processedThrough,
          firstReason(MODEL_INVALID, partialReason),
          forbiddenMessageGuids);
    }
    if (budget.modelBatches >= maxModelBatches
        || reductionCharacters > maxBatchCharacters
        || reductionCharacters > maxAggregateCharacters - budget.characters) {
      return bestFinding(
          findings,
          processedThrough,
          firstReason(
              budget.modelBatches >= maxModelBatches ? MODEL_BATCH_LIMIT : CHARACTER_LIMIT,
              partialReason),
          forbiddenMessageGuids);
    }
    budget.modelBatches++;
    budget.characters += reductionCharacters;
    try {
      RoutedReductionAnswer reduction =
          model.reduceWithCitations(question, questionFindings, deadline);
      RoutedModelAnswer reduced = reduction.routed();
      ModelAnswer validated = validateAnswer(reduced, submittedEvidence, forbiddenMessageGuids);
      if (validated == null) {
        return bestFinding(
            findings,
            processedThrough,
            firstReason(MODEL_INVALID, partialReason),
            forbiddenMessageGuids);
      }
      RoutedModelAnswer accepted = reduced;
      if (validated.status() == AnswerStatus.ANSWERED) {
        VerificationOutcome verification =
            verifyReducedAnswer(
                question, reduced, questionFindings, reduction.citedFindings(), deadline, budget);
        if (verification.failureReason() != null) {
          return bestFinding(
              findings,
              processedThrough,
              firstReason(verification.failureReason(), partialReason),
              forbiddenMessageGuids);
        }
        accepted = Objects.requireNonNull(verification.routed());
        validated = accepted.answer();
        if (validated.status() != AnswerStatus.ANSWERED) {
          return bestFinding(
              findings,
              processedThrough,
              firstReason(NEEDS_MORE_CONTEXT, partialReason),
              forbiddenMessageGuids);
        }
      }
      String reason =
          dominantReason(
              partialReason,
              validated.status() == AnswerStatus.UNAVAILABLE ? MODEL_UNAVAILABLE : null,
              validated.needsMoreContext() ? NEEDS_MORE_CONTEXT : null,
              deadlineReached(deadline) ? TIME_LIMIT : null);
      return new Synthesis(
          accepted, processedThrough, reason, validated.status() == AnswerStatus.UNAVAILABLE);
    } catch (RuntimeException ignored) {
      return bestFinding(
          findings,
          processedThrough,
          firstReason(MODEL_UNAVAILABLE, partialReason),
          forbiddenMessageGuids);
    }
  }

  private Synthesis bestFinding(
      List<SupportedFinding> findings,
      Instant processedThrough,
      String partialReason,
      Set<String> forbiddenMessageGuids) {
    SupportedFinding selected =
        findings.stream()
            .max(
                Comparator.comparingInt(
                        (SupportedFinding finding) ->
                            confidenceRank(finding.finding().confidence()))
                    .thenComparing(finding -> finding.finding().coverageThrough()))
            .orElseThrow();
    if (!ConversationQuestionAnswerOutputValidator.isSafe(
        selected.routed().answer().answer(), forbiddenMessageGuids, Set.of())) {
      return new Synthesis(
          null, processedThrough, firstReason(MODEL_INVALID, partialReason), false);
    }
    return new Synthesis(selected.routed(), processedThrough, partialReason, false);
  }

  private Batch nextBatch(
      String question,
      List<QuestionMessage> messages,
      int startIndex,
      int aggregateCharactersRemaining) {
    List<QuestionMessage> batch = new ArrayList<>();
    int characters = 0;
    int nextIndex = startIndex;
    while (nextIndex < messages.size() && batch.size() < maxBatchMessages) {
      QuestionMessage message = messages.get(nextIndex);
      List<QuestionMessage> candidate = new ArrayList<>(batch);
      candidate.add(message);
      int candidateCharacters = model.answerInputCharacters(question, candidate);
      if (candidateCharacters > maxBatchCharacters
          || candidateCharacters > aggregateCharactersRemaining) {
        break;
      }
      batch.add(message);
      characters = candidateCharacters;
      nextIndex++;
    }
    return new Batch(List.copyOf(batch), characters, nextIndex);
  }

  private ModelAnswer validateAnswer(
      @Nullable RoutedModelAnswer routed,
      Set<String> submittedEvidence,
      Set<String> forbiddenMessageGuids) {
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
    if (!ConversationQuestionAnswerOutputValidator.isSafe(
        answer.answer(), forbiddenMessageGuids, Set.of())) {
      return null;
    }
    return answer;
  }

  private VerificationOutcome verifyBatchAnswer(
      String question,
      RoutedModelAnswer proposed,
      List<QuestionMessage> submittedMessages,
      Instant deadline,
      ModelBudget budget) {
    Set<String> cited = Set.copyOf(proposed.answer().evidenceMessageGuids());
    List<QuestionMessage> citedMessages =
        submittedMessages.stream()
            .filter(message -> cited.contains(message.messageGuid()))
            .toList();
    if (citedMessages.isEmpty() || deadlineReached(deadline)) {
      return VerificationOutcome.failure(citedMessages.isEmpty() ? MODEL_INVALID : TIME_LIMIT);
    }
    try {
      int verificationCharacters =
          model.verificationInputCharacters(question, proposed.answer().answer(), citedMessages);
      if (verificationCharacters > maxBatchCharacters
          || verificationCharacters > maxAggregateCharacters - budget.characters) {
        return VerificationOutcome.failure(CHARACTER_LIMIT);
      }
      budget.characters += verificationCharacters;
      budget.verificationCalls++;
      RoutedSupportVerification verification =
          model.verifyAnswer(question, proposed.answer().answer(), citedMessages, deadline);
      return VerificationOutcome.success(applyVerification(proposed, verification));
    } catch (RuntimeException ignored) {
      return VerificationOutcome.failure(MODEL_UNAVAILABLE);
    }
  }

  private VerificationOutcome verifyReducedAnswer(
      String question,
      RoutedModelAnswer proposed,
      List<QuestionFinding> submittedFindings,
      List<QuestionFinding> citedFindings,
      Instant deadline,
      ModelBudget budget) {
    Set<String> cited = Set.copyOf(proposed.answer().evidenceMessageGuids());
    Set<String> expandedFindingEvidence = findingGuids(citedFindings);
    boolean invalidCitations =
        citedFindings.isEmpty()
            || !new LinkedHashSet<>(submittedFindings).containsAll(citedFindings)
            || !expandedFindingEvidence.equals(cited);
    if (invalidCitations || deadlineReached(deadline)) {
      return VerificationOutcome.failure(invalidCitations ? MODEL_INVALID : TIME_LIMIT);
    }
    try {
      int verificationCharacters =
          model.reductionVerificationInputCharacters(
              question, proposed.answer().answer(), citedFindings);
      if (verificationCharacters > maxBatchCharacters
          || verificationCharacters > maxAggregateCharacters - budget.characters) {
        return VerificationOutcome.failure(CHARACTER_LIMIT);
      }
      budget.characters += verificationCharacters;
      budget.verificationCalls++;
      RoutedSupportVerification verification =
          model.verifyReduction(question, proposed.answer().answer(), citedFindings, deadline);
      return VerificationOutcome.success(applyVerification(proposed, verification));
    } catch (RuntimeException ignored) {
      return VerificationOutcome.failure(MODEL_UNAVAILABLE);
    }
  }

  private static RoutedModelAnswer applyVerification(
      RoutedModelAnswer proposed, RoutedSupportVerification verification) {
    boolean fallbackUsed = proposed.fallbackUsed() || verification.fallbackUsed();
    String routedModel = verification.fallbackUsed() ? verification.model() : proposed.model();
    if (verification.supported()) {
      return new RoutedModelAnswer(proposed.answer(), routedModel, fallbackUsed);
    }
    return new RoutedModelAnswer(
        new ModelAnswer(
            AnswerStatus.INSUFFICIENT_EVIDENCE,
            INSUFFICIENT_ANSWER,
            Confidence.LOW,
            List.of(),
            true),
        routedModel,
        fallbackUsed);
  }

  private GroupQuestionAnswer finalAnswer(
      Synthesis synthesis,
      @Nullable RetrievalResult retrieval,
      RetrievalMode mode,
      Instant from,
      Instant to,
      @Nullable String forcedPartialReason,
      Set<String> forbiddenMessageGuids) {
    ModelAnswer answer = synthesis.routed().answer();
    String reason =
        dominantReason(
            forcedPartialReason,
            synthesis.partialReason(),
            retrieval == null ? null : retrieval.partialReason());
    if (answer.status() == AnswerStatus.ANSWERED
        && !ConversationQuestionAnswerOutputValidator.isSafe(
            answer.answer(), forbiddenMessageGuids, Set.of())) {
      return terminalAnswer(
          AnswerStatus.INSUFFICIENT_EVIDENCE,
          from,
          to,
          mode,
          CoverageStatus.PARTIAL,
          dominantReason(MODEL_INVALID, reason),
          synthesis.coverageThrough(),
          synthesis.routed().model(),
          synthesis.routed().fallbackUsed());
    }
    CoverageStatus coverage = reason == null ? CoverageStatus.COMPLETE : CoverageStatus.PARTIAL;
    Instant coverageThrough =
        coverage == CoverageStatus.COMPLETE
            ? retrieval == null ? to : retrieval.coverageThrough()
            : synthesis.coverageThrough();
    return new GroupQuestionAnswer(
        answer.status(),
        answer.answer(),
        answer.confidence(),
        synthesis.routed().model(),
        synthesis.routed().fallbackUsed(),
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
    RoutedModelAnswer routed = synthesis.routed();
    return terminalAnswer(
        status,
        from,
        to,
        mode,
        coverage,
        reason,
        coverageThrough,
        routed == null ? null : routed.model(),
        routed != null && routed.fallbackUsed());
  }

  private GroupQuestionAnswer supportedBackupOrUnavailable(
      @Nullable Synthesis backup,
      @Nullable RetrievalResult retrieval,
      RetrievalMode mode,
      Instant from,
      Instant to,
      String reason,
      Set<String> forbiddenMessageGuids) {
    if (backup != null && backup.supported()) {
      return finalAnswer(backup, retrieval, mode, from, to, reason, forbiddenMessageGuids);
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
        AnswerStatus.INSUFFICIENT_EVIDENCE,
        from,
        to,
        mode,
        coverage,
        reason,
        coverageThrough,
        null,
        false);
  }

  private GroupQuestionAnswer unavailable(
      Instant from, Instant to, RetrievalMode mode, String reason, Instant coverageThrough) {
    return terminalAnswer(
        AnswerStatus.UNAVAILABLE,
        from,
        to,
        mode,
        CoverageStatus.PARTIAL,
        reason,
        coverageThrough,
        null,
        false);
  }

  private GroupQuestionAnswer terminalAnswer(
      AnswerStatus status,
      Instant from,
      Instant to,
      RetrievalMode mode,
      CoverageStatus coverage,
      @Nullable String reason,
      Instant coverageThrough,
      @Nullable String modelName,
      boolean fallbackUsed) {
    return new GroupQuestionAnswer(
        status,
        status == AnswerStatus.UNAVAILABLE ? UNAVAILABLE_ANSWER : INSUFFICIENT_ANSWER,
        Confidence.LOW,
        modelName,
        fallbackUsed,
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
    private int plannerCalls;
    private int verificationCalls;
    private int characters;
  }

  private static final class QuestionAnswerWork {
    private final Set<String> messageGuids = new LinkedHashSet<>();
    private long pageCount;

    private void observe(RetrievalResult retrieval) {
      retrieval.messages().stream().map(QuestionMessage::messageGuid).forEach(messageGuids::add);
      pageCount += retrieval.pageCount();
    }

    private long messageCount() {
      return messageGuids.size();
    }

    private Set<String> messageGuids() {
      return Set.copyOf(messageGuids);
    }
  }

  private record Batch(List<QuestionMessage> messages, int characters, int nextIndex) {}

  private record SupportedFinding(QuestionFinding finding, RoutedModelAnswer routed) {}

  private record VerificationOutcome(
      @Nullable RoutedModelAnswer routed, @Nullable String failureReason) {
    private static VerificationOutcome success(RoutedModelAnswer routed) {
      return new VerificationOutcome(routed, null);
    }

    private static VerificationOutcome failure(String reason) {
      return new VerificationOutcome(null, reason);
    }
  }

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
