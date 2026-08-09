package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationQuestionAnsweringServiceTest {
  private static final String ACCOUNT = "account-1";
  private static final String CONVERSATION_ID = "conversation-1";
  private static final String QUESTION = "Who is winning the current Wordle?";
  private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-09T00:01:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(90);
  private static final AuthorizedGroup GROUP =
      new AuthorizedGroup(CONVERSATION_ID, "Wordling Wonders", TO);
  private static final ConversationRecord CONVERSATION =
      new ConversationRecord(
          CONVERSATION_ID,
          "bluebubbles",
          "iMessage;+;group",
          true,
          "Wordling Wonders",
          FROM.minusSeconds(1),
          ACCOUNT,
          TO);
  private static final SearchPlan WORDLE_PLAN = new SearchPlan(List.of("Wordle"), null, null, null);

  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final ConversationQuestionHistoryRetriever retriever =
      mock(ConversationQuestionHistoryRetriever.class);
  private final ConversationQuestionAnsweringModelClient model =
      mock(ConversationQuestionAnsweringModelClient.class);
  private final MutableClock clock = new MutableClock(NOW);
  private ConversationQuestionAnsweringService service;

  @BeforeEach
  void setUp() {
    service = service(100, 60_000, 5, 300_000);
    when(store.findConversation(CONVERSATION_ID)).thenReturn(Optional.of(CONVERSATION));
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, FROM, TO))
        .thenReturn(List.of(new MembershipInterval(FROM, null)));
    when(model.plan(QUESTION, FROM, TO, DEADLINE)).thenReturn(WORDLE_PLAN);
  }

  @Test
  void returnsOnlyReportedLeaderFromExactEvidence() {
    QuestionMessage score = message("score", "participant ending 0199", "Wordle 1,877 4/6", 1);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(
            new RoutedModelAnswer(
                answered("The only reported score is participant ending 0199 with 4/6.", "score"),
                "openai/gpt-4.1-mini",
                true));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).contains("only reported").contains("4/6");
    assertThat(result.evidenceMessageCount()).isEqualTo(1);
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.EXACT_SEARCH);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.coverageThrough()).isEqualTo(TO);
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void comparesSamePuzzleScoresWithoutUsingSemanticMemory() {
    List<QuestionMessage> evidence =
        List.of(
            message("score-1", "participant ending 0199", "Wordle 1,877 4/6", 1),
            message("score-2", "participant ending 0123", "Wordle 1,877 3/6", 2),
            message("score-old", "participant ending 0456", "Wordle 1,876 2/6", 3));
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(evidence));
    when(model.answer(QUESTION, evidence, DEADLINE))
        .thenReturn(
            routed(
                answered(
                    "Of the reported Wordle 1,877 scores, participant ending 0123 leads with 3/6.",
                    "score-2")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.answer()).contains("3/6").doesNotContain("league");
    ArgumentCaptor<List<QuestionMessage>> submitted = listCaptor();
    verify(model).answer(eq(QUESTION), submitted.capture(), eq(DEADLINE));
    assertThat(submitted.getValue())
        .extracting(QuestionMessage::text)
        .allMatch(text -> text.startsWith("Wordle"));
  }

  @Test
  void exactMissUsesOneChronologicalFallback() {
    QuestionMessage score = message("score", "participant ending 0199", "Wordle 1,877 4/6", 1);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "score")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    verify(retriever, times(1)).retrieveChronological(any());
    verify(model, times(1)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void plannerFailureSkipsExactSourceWorkAndUsesChronologicalEvidence() {
    SearchPlan emptyPlan = new SearchPlan(List.of(), null, null, null);
    QuestionMessage score = message("score", "participant ending 0199", "Wordle 1,877 4/6", 1);
    when(model.plan(QUESTION, FROM, TO, DEADLINE))
        .thenThrow(new IllegalStateException("provider failed"));
    when(retriever.retrieveExact(any(), eq(emptyPlan))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "score")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.CHRONOLOGICAL);
    verify(retriever).retrieveExact(any(), eq(emptyPlan));
    verify(retriever, times(1)).retrieveChronological(any());
  }

  @Test
  void needsMoreContextProducesOnlyOneChronologicalRetry() {
    QuestionMessage exact = message("exact", "participant ending 0199", "Wordle 1,877 4/6", 1);
    QuestionMessage context = message("context", "participant ending 0123", "Wordle 1,877 3/6", 2);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of(exact)));
    when(retriever.retrieveChronological(any()))
        .thenReturn(completeChronological(List.of(context)));
    when(model.answer(QUESTION, List.of(exact), DEADLINE))
        .thenReturn(
            routed(
                new ModelAnswer(
                    AnswerStatus.ANSWERED,
                    "Participant ending 0199 reported 4/6.",
                    Confidence.LOW,
                    List.of("exact"),
                    true)));
    when(model.answer(QUESTION, List.of(context), DEADLINE))
        .thenReturn(routed(answered("The only contextual result is 3/6.", "context")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.answer()).contains("3/6");
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    verify(retriever, times(1)).retrieveChronological(any());
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void boundsEveryExactEvidenceBatchAndReducesSupportedFindings() {
    List<QuestionMessage> messages = messages(101, 10);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(
            new RoutedModelAnswer(
                answered("The reduced exact answer.", "message-0", "message-100"),
                "openai/gpt-4.1-mini",
                true));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.EXACT_SEARCH);
    assertThat(result.evidenceMessageCount()).isEqualTo(2);
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
    assertSubmittedBatchBounds(2, 100, 60_000);
    verify(model).reduce(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void chronologicalFallbackBatchesByMessageCountAndReducesFindings() {
    List<QuestionMessage> messages = messages(101, 10);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(
            routed(answered("The reduced chronological answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.evidenceMessageCount()).isEqualTo(2);
    assertSubmittedBatchBounds(2, 100, 60_000);
    ArgumentCaptor<List<QuestionFinding>> findings = findingListCaptor();
    verify(model).reduce(eq(QUESTION), findings.capture(), eq(DEADLINE));
    assertThat(findings.getValue()).hasSize(2);
  }

  @Test
  void chronologicalNeedsMoreContextContinuesRemainingBatchesAndReducesFindings() {
    List<QuestionMessage> messages = messages(101, 10);
    exactMissThenChronological(messages);
    when(model.answer(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(1);
              String evidenceGuid = batch.getFirst().messageGuid();
              if (evidenceGuid.equals("message-0")) {
                return routed(
                    new ModelAnswer(
                        AnswerStatus.ANSWERED,
                        "The first batch needs later context.",
                        Confidence.LOW,
                        List.of(evidenceGuid),
                        true));
              }
              return routed(answered("The later batch supports the answer.", evidenceGuid));
            });
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("The reduced complete answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.answer()).isEqualTo("The reduced complete answer.");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model).reduce(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void chronologicalFallbackBatchesByCharacterCount() {
    List<QuestionMessage> messages = messages(3, 30_001);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(
            routed(
                answered(
                    "The reduced character-bounded answer.",
                    "message-0",
                    "message-1",
                    "message-2")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertSubmittedBatchBounds(3, 100, 60_000);
  }

  @Test
  void modelBatchLimitReturnsBestSupportedPartialAnswer() {
    service = service(100, 60_000, 2, 300_000);
    List<QuestionMessage> messages = messages(201, 10);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("The supported partial result.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("model_batch_limit");
    assertThat(result.coverageThrough()).isEqualTo(messages.get(199).timestamp());
    assertThat(result.evidenceMessageCount()).isEqualTo(2);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void aggregateCharacterLimitReturnsBestSupportedPartialAnswer() {
    service = service(100, 60_000, 5, 100_000);
    List<QuestionMessage> messages = messages(3, 50_000);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("The supported partial result.", "message-0", "message-1")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    assertThat(result.coverageThrough()).isEqualTo(messages.get(1).timestamp());
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void exactFiveBatchEqualityIsCompleteWhenAllEvidenceWasProcessed() {
    List<QuestionMessage> messages = messages(500, 10);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(
            routed(
                answered(
                    "All five exact batches were reduced.",
                    "message-0",
                    "message-100",
                    "message-200",
                    "message-300",
                    "message-400")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.partialReason()).isNull();
    verify(model, times(5)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void exactAggregateCharacterEqualityIsCompleteWhenAllEvidenceWasProcessed() {
    service = service(100, 60_000, 5, 100_000);
    List<QuestionMessage> messages = messages(2, 50_000);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("Both exact batches were reduced.", "message-0", "message-1")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.partialReason()).isNull();
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void oneNinetySecondDeadlineIsSharedAndStopsFallbackWork() {
    ArgumentCaptor<RetrievalRequest> exactRequest = ArgumentCaptor.forClass(RetrievalRequest.class);
    when(retriever.retrieveExact(exactRequest.capture(), eq(WORDLE_PLAN)))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofSeconds(90));
              return completeExact(List.of());
            });

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(exactRequest.getValue().deadline()).isEqualTo(NOW.plusSeconds(90));
    assertThat(result.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("time_limit");
    verify(retriever, never()).retrieveChronological(any());
    verify(model, never()).answer(eq(QUESTION), anyList(), any());
  }

  @Test
  void usesOneOperationDeadlineForEveryModelBoundary() {
    List<QuestionMessage> messages = messages(101, 10);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    when(model.reduce(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("The reduced answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    verify(model).plan(QUESTION, FROM, TO, DEADLINE);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model).reduce(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void modelCallCompletingAtTheDeadlineCanOnlyReturnSupportedPartialEvidence() {
    QuestionMessage score = message("score", "participant ending 0199", "Wordle 1,877 4/6", 1);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofSeconds(90));
              return routed(answered("The only reported score is 4/6.", "score"));
            });

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("time_limit");
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void sourceCoverageFailureIsNotHiddenByAModelRequestForMoreContext() {
    QuestionMessage score = message("score", "participant ending 0199", "Wordle 1,877 4/6", 1);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any()))
        .thenReturn(
            new RetrievalResult(
                List.of(score),
                RetrievalMode.CHRONOLOGICAL,
                CoverageStatus.PARTIAL,
                score.timestamp(),
                "source_unavailable",
                1));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(
            routed(
                new ModelAnswer(
                    AnswerStatus.ANSWERED,
                    "The only reported score is 4/6.",
                    Confidence.LOW,
                    List.of("score"),
                    true)));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    verify(retriever, times(1)).retrieveChronological(any());
  }

  @Test
  void sourceAndModelExceptionsNeverEscapeAndCanStillReturnSupportedPartialEvidence() {
    List<QuestionMessage> messages = messages(101, 10);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN)))
        .thenThrow(new IllegalStateException("source unavailable"));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(messages));
    when(model.answer(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(1);
              if (batch.get(0).messageGuid().equals("message-100")) {
                throw new IllegalStateException("provider unavailable");
              }
              return new RoutedModelAnswer(
                  answered("A supported first-batch result.", "message-0"),
                  "openai/gpt-4.1-mini",
                  true);
            });

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).isEqualTo("A supported first-batch result.");
    assertThat(result.evidenceMessageCount()).isEqualTo(1);
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("model_unavailable");
    assertThat(result.coverageThrough()).isEqualTo(messages.get(99).timestamp());
    verify(model, never()).reduce(eq(QUESTION), anyList(), any());
  }

  @Test
  void totalSourceFailureReturnsUnavailableWithoutThrowing() {
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN)))
        .thenThrow(new IllegalStateException("exact unavailable"));
    when(retriever.retrieveChronological(any()))
        .thenThrow(new IllegalStateException("chronological unavailable"));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(result.evidenceMessageCount()).isZero();
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
  }

  @Test
  void unsupportedEvidenceIsNotReportedAndDoesNotCauseAnotherFallbackLoop() {
    QuestionMessage exact = message("exact", "participant ending 0199", "Wordle 1,877 4/6", 1);
    QuestionMessage chronological =
        message("chronological", "participant ending 0123", "Wordle 1,877 3/6", 2);
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of(exact)));
    when(retriever.retrieveChronological(any()))
        .thenReturn(completeChronological(List.of(chronological)));
    when(model.answer(QUESTION, List.of(exact), DEADLINE))
        .thenReturn(routed(answered("Unsupported exact answer.", "not-submitted")));
    when(model.answer(QUESTION, List.of(chronological), DEADLINE))
        .thenReturn(routed(answered("Unsupported chronological answer.", "not-submitted")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.evidenceMessageCount()).isZero();
    verify(retriever, times(1)).retrieveChronological(any());
  }

  @Test
  void emptyMembershipReturnsInsufficientEvidenceWithoutHistoryOrModelAccess() {
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, FROM, TO)).thenReturn(List.of());

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.evidenceMessageCount()).isZero();
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("unauthorized_range");
    verifyNoInteractions(retriever);
    verifyNoInteractions(model);
  }

  private ConversationQuestionAnsweringService service(
      int maxBatchMessages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters) {
    return new ConversationQuestionAnsweringService(
        store,
        retriever,
        model,
        maxBatchMessages,
        maxBatchCharacters,
        maxModelBatches,
        maxAggregateCharacters,
        Duration.ofSeconds(90),
        clock);
  }

  private void exactMissThenChronological(List<QuestionMessage> messages) {
    when(retriever.retrieveExact(any(), eq(WORDLE_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(messages));
  }

  private void answerEachBatchWithItsFirstEvidence() {
    when(model.answer(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(1);
              String evidenceGuid = batch.get(0).messageGuid();
              return routed(answered("Supported finding for " + evidenceGuid + ".", evidenceGuid));
            });
  }

  private void assertSubmittedBatchBounds(
      int expectedBatchCount, int maxMessages, int maxCharacters) {
    ArgumentCaptor<List<QuestionMessage>> batches = listCaptor();
    verify(model, times(expectedBatchCount)).answer(eq(QUESTION), batches.capture(), eq(DEADLINE));
    assertThat(batches.getAllValues()).hasSize(expectedBatchCount);
    assertThat(batches.getAllValues())
        .allSatisfy(
            batch -> {
              assertThat(batch).hasSizeLessThanOrEqualTo(maxMessages);
              assertThat(batch.stream().mapToInt(message -> message.text().length()).sum())
                  .isLessThanOrEqualTo(maxCharacters);
            });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<QuestionMessage>> listCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<QuestionFinding>> findingListCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }

  private static List<QuestionMessage> messages(int count, int textLength) {
    List<QuestionMessage> messages = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String prefix = "m" + index + "-";
      String text = prefix + "x".repeat(Math.max(0, textLength - prefix.length()));
      messages.add(
          message(
              "message-" + index,
              "participant ending %04d".formatted(index % 10_000),
              text,
              index));
    }
    return List.copyOf(messages);
  }

  private static QuestionMessage message(
      String guid, String participant, String text, int secondsAfterFrom) {
    return new QuestionMessage(guid, participant, FROM.plusSeconds(secondsAfterFrom), text);
  }

  private static RetrievalResult completeExact(List<QuestionMessage> messages) {
    return new RetrievalResult(
        messages, RetrievalMode.EXACT_SEARCH, CoverageStatus.COMPLETE, TO, null, 1);
  }

  private static RetrievalResult completeChronological(List<QuestionMessage> messages) {
    return new RetrievalResult(
        messages, RetrievalMode.CHRONOLOGICAL, CoverageStatus.COMPLETE, TO, null, 1);
  }

  private static ModelAnswer answered(String answer, String... evidenceGuids) {
    return new ModelAnswer(
        AnswerStatus.ANSWERED, answer, Confidence.HIGH, List.of(evidenceGuids), false);
  }

  private static RoutedModelAnswer routed(ModelAnswer answer) {
    return new RoutedModelAnswer(answer, "openrouter/z-ai/glm-5.2");
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
