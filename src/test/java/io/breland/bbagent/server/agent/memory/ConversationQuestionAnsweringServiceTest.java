package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedReductionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RoutedSupportVerification;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationQuestionAnsweringServiceTest {
  private static final String ACCOUNT = "account-1";
  private static final String CONVERSATION_ID = "conversation-1";
  private static final String QUESTION = "Who is leading the current challenge?";
  private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-09T00:01:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(90);
  private static final AuthorizedGroup GROUP =
      new AuthorizedGroup(CONVERSATION_ID, "Weekend Updates", TO);
  private static final ConversationRecord CONVERSATION =
      new ConversationRecord(
          CONVERSATION_ID,
          "bluebubbles",
          "iMessage;+;group",
          true,
          "Weekend Updates",
          FROM.minusSeconds(1),
          ACCOUNT,
          TO);
  private static final SearchPlan REPORT_PLAN =
      new SearchPlan(List.of("reported"), null, null, null);

  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final ConversationQuestionHistoryRetriever retriever =
      mock(ConversationQuestionHistoryRetriever.class);
  private final ConversationQuestionAnsweringModelClient model =
      mock(ConversationQuestionAnsweringModelClient.class);
  private final ConversationQuestionAnsweringModelClient payloadSizer =
      new ConversationQuestionAnsweringModelClient(
          mock(ConversationMemoryResponsesClient.class),
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  private final MutableClock clock = new MutableClock(NOW);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OperationalMetricsService metrics = new OperationalMetricsService(registry);
  private ConversationQuestionAnsweringService service;

  @BeforeEach
  void setUp() {
    service = service(100, 60_000, 5, 300_000);
    when(store.findConversation(CONVERSATION_ID)).thenReturn(Optional.of(CONVERSATION));
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, FROM, TO))
        .thenReturn(List.of(new MembershipInterval(FROM, null)));
    when(model.plan(QUESTION, FROM, TO, DEADLINE)).thenReturn(REPORT_PLAN);
    when(model.answerInputCharacters(anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.answerInputCharacters(
                    invocation.getArgument(0), invocation.getArgument(1)));
    when(model.answerWorkCharacters(anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.answerWorkCharacters(
                    invocation.getArgument(0), invocation.getArgument(1)));
    when(model.reduceWorkCharacters(anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.reduceWorkCharacters(
                    invocation.getArgument(0), invocation.getArgument(1)));
    when(model.reduceInputCharacters(anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.reduceInputCharacters(
                    invocation.getArgument(0), invocation.getArgument(1)));
    when(model.verificationInputCharacters(anyString(), anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.verificationInputCharacters(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(model.reductionVerificationInputCharacters(anyString(), anyString(), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.reductionVerificationInputCharacters(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(model.verifyAnswer(anyString(), anyString(), anyList(), any()))
        .thenReturn(supportedVerification());
    when(model.verifyReduction(anyString(), anyString(), anyList(), any()))
        .thenReturn(supportedVerification());
  }

  @Test
  void unsupportedExactAttributionFallsBackToVerifiedChronologicalEvidence() {
    String question = "Who owns Project Atlas and what is its status?";
    SearchPlan plan = new SearchPlan(List.of("Project Atlas"), null, null, null);
    QuestionMessage exact = message("exact", "Alice", "I own Project Atlas.", 1);
    QuestionMessage chronological =
        message("chronological", "Alice", "Project Atlas is ready for review.", 2);
    when(model.plan(question, FROM, TO, DEADLINE)).thenReturn(plan);
    when(retriever.retrieveExact(any(), eq(plan))).thenReturn(completeExact(List.of(exact)));
    when(retriever.retrieveChronological(any()))
        .thenReturn(completeChronological(List.of(chronological)));
    when(model.answer(question, List.of(exact), DEADLINE))
        .thenReturn(routed(answered("Bob owns Project Atlas.", "exact")));
    when(model.verifyAnswer(question, "Bob owns Project Atlas.", List.of(exact), DEADLINE))
        .thenReturn(new RoutedSupportVerification(false, "openrouter/z-ai/glm-5.2", false));
    when(model.answer(question, List.of(chronological), DEADLINE))
        .thenReturn(routed(answered("Alice said Atlas is review-ready.", "chronological")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, question, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).isEqualTo("Alice said Atlas is review-ready.");
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    verify(retriever).retrieveChronological(any());
    assertQuestionModelWork(2, 1, 2);
  }

  @Test
  void verifierReceivesOnlyMessagesCitedByTheProposedAnswer() {
    QuestionMessage cited = message("cited", "Alice", "Inventory is 84 units.", 1);
    QuestionMessage uncited = message("uncited", "Bob", "Private unrelated details.", 2);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenReturn(completeExact(List.of(cited, uncited)));
    when(model.answer(QUESTION, List.of(cited, uncited), DEADLINE))
        .thenReturn(routed(answered("Alice reported 84 units.", "cited")));

    service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuestionMessage>> evidence = ArgumentCaptor.forClass(List.class);
    verify(model)
        .verifyAnswer(
            eq(QUESTION), eq("Alice reported 84 units."), evidence.capture(), eq(DEADLINE));
    assertThat(evidence.getValue()).containsExactly(cited).doesNotContain(uncited);
  }

  @Test
  void verifierProviderFailureIsContainedWithoutReturningTheUnverifiedAnswer() {
    QuestionMessage exact = message("exact", "Alice", "The launch inventory is 84 units.", 1);
    QuestionMessage chronological =
        message("chronological", "Alice", "The launch inventory remains 84 units.", 2);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(exact)));
    when(retriever.retrieveChronological(any()))
        .thenReturn(completeChronological(List.of(chronological)));
    when(model.answer(QUESTION, List.of(exact), DEADLINE))
        .thenReturn(routed(answered("Alice reported 84 units.", "exact")));
    when(model.answer(QUESTION, List.of(chronological), DEADLINE))
        .thenReturn(routed(answered("Alice still reported 84 units.", "chronological")));
    when(model.verifyAnswer(anyString(), anyString(), anyList(), eq(DEADLINE)))
        .thenThrow(new IllegalStateException("verifier unavailable"));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(result.partialReason()).isEqualTo("model_unavailable");
    assertThat(result.answer())
        .doesNotContain("Alice reported 84 units", "Alice still reported 84 units");
    verify(model, times(2)).verifyAnswer(anyString(), anyString(), anyList(), eq(DEADLINE));
    assertQuestionModelWork(2, 1, 2);
  }

  @Test
  void verifierFallbackModelMetadataIsReportedHonestly() {
    QuestionMessage evidence =
        message("inventory", "Alice", "The launch inventory is 84 units.", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenReturn(completeExact(List.of(evidence)));
    when(model.answer(QUESTION, List.of(evidence), DEADLINE))
        .thenReturn(routed(answered("Alice reported 84 units.", "inventory")));
    when(model.verifyAnswer(QUESTION, "Alice reported 84 units.", List.of(evidence), DEADLINE))
        .thenReturn(new RoutedSupportVerification(true, "openai/gpt-4.1-mini", true));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
  }

  @Test
  void overlongQuestionReturnsSafeTerminalResultWithoutSourceOrModelAccess() {
    String overlongQuestion = "q".repeat(4_001);

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, overlongQuestion, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.answer()).doesNotContain(overlongQuestion);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    verifyNoInteractions(store, retriever);
    verify(model, never()).plan(anyString(), any(), any(), any());
    verify(model, never()).answer(anyString(), anyList(), any());
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertQuestionModelWork(0, 0, 0);
  }

  @Test
  void serializedEscapingOverheadCannotCrossBatchCharacterLimit() {
    String escapedText = "\"\\\n\t123456";
    QuestionMessage escaped = message("escaped", "Dom", escapedText, 1);
    int rawCharacters = QUESTION.length() + escapedText.length();
    service = service(100, rawCharacters, 5, rawCharacters);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenReturn(completeExact(List.of(escaped)));
    when(retriever.retrieveChronological(any()))
        .thenReturn(completeChronological(List.of(escaped)));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    verify(model, never()).answer(anyString(), anyList(), any());
  }

  @Test
  void repeatedQuestionAndSerializationOverheadCountAgainstAggregateBudget() {
    QuestionMessage first = message("first", "Dom", "Challenge round 1,877 result 4/6", 1);
    QuestionMessage second = message("second", "Alice", "Challenge round 1,877 result 3/6", 2);
    int generationCharacters = payloadSizer.answerInputCharacters(QUESTION, List.of(first));
    int verificationCharacters =
        payloadSizer.verificationInputCharacters(
            QUESTION, "Dom only reported 4/6.", List.of(first));
    service =
        service(
            1,
            Math.max(generationCharacters, verificationCharacters),
            5,
            generationCharacters + verificationCharacters);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenReturn(completeExact(List.of(first, second)));
    when(model.answer(QUESTION, List.of(first), DEADLINE))
        .thenReturn(routed(answered("Dom only reported 4/6.", "first")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    verify(model, times(1)).answer(QUESTION, List.of(first), DEADLINE);
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void returnsOnlyReportedLeaderFromExactEvidence() {
    QuestionMessage score =
        message("score-guid", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(
            new RoutedModelAnswer(
                answered(
                    "The only reported score is participant ending 0199 with 4/6.", "score-guid"),
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
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertThat(
            registry
                .get("bbagent.memory.question.answer.count")
                .tag("retrieval_mode", "exact_search")
                .tag("coverage_status", "complete")
                .tag("model", "openai/gpt-4.1-mini")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.message.count").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.page.count").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.model.batch.count").counter().count())
        .isEqualTo(1.0);
    assertQuestionModelWork(1, 1, 1);
  }

  @Test
  void comparesSamePuzzleScoresWithoutUsingSemanticMemory() {
    List<QuestionMessage> evidence =
        List.of(
            message("score-1", "participant ending 0199", "Wordle 1,877 4/6", 1),
            message("score-2", "participant ending 0123", "Wordle 1,877 3/6", 2),
            message("score-old", "participant ending 0456", "Wordle 1,876 2/6", 3));
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(evidence));
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
    QuestionMessage score =
        message("score-guid", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "score-guid")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    verify(retriever, times(1)).retrieveChronological(any());
    verify(model, times(1)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void plannerFailureSkipsExactSourceWorkAndUsesChronologicalEvidence() {
    SearchPlan emptyPlan = new SearchPlan(List.of(), null, null, null);
    QuestionMessage score =
        message("score-guid", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(model.plan(QUESTION, FROM, TO, DEADLINE))
        .thenThrow(new IllegalStateException("provider failed"));
    when(retriever.retrieveExact(any(), eq(emptyPlan))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "score-guid")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.CHRONOLOGICAL);
    verify(retriever).retrieveExact(any(), eq(emptyPlan));
    verify(retriever, times(1)).retrieveChronological(any());
  }

  @Test
  void needsMoreContextProducesOnlyOneChronologicalRetry() {
    QuestionMessage exact =
        message("exact", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    QuestionMessage context =
        message("context-guid", "participant ending 0123", "Challenge round 1,877 result 3/6", 2);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(exact)));
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
        .thenReturn(routed(answered("The only contextual result is 3/6.", "context-guid")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.answer()).contains("3/6");
    assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    verify(retriever, times(1)).retrieveChronological(any());
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void boundsEveryExactEvidenceBatchAndReducesSupportedFindings() {
    List<QuestionMessage> messages = messages(101, 10);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    stubReduction(
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
    verify(model).reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void chronologicalFallbackBatchesByMessageCountAndReducesFindings() {
    List<QuestionMessage> messages = messages(101, 10);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    stubReduction(
        routed(answered("The reduced chronological answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.evidenceMessageCount()).isEqualTo(2);
    assertSubmittedBatchBounds(2, 100, 60_000);
    ArgumentCaptor<List<QuestionFinding>> findings = findingListCaptor();
    verify(model).reduceWithCitations(eq(QUESTION), findings.capture(), eq(DEADLINE));
    assertThat(findings.getValue()).hasSize(2);
    assertQuestionModelWork(3, 1, 3);
  }

  @Test
  void reducedAnswerVerifierReceivesOnlyTheCitedVerifiedFinding() {
    List<QuestionMessage> messages = messages(101, 10);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    stubReduction(routed(answered("The later race result is decisive.", "message-100")));

    service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuestionFinding>> citedFindings = ArgumentCaptor.forClass(List.class);
    verify(model)
        .verifyReduction(
            eq(QUESTION),
            eq("The later race result is decisive."),
            citedFindings.capture(),
            eq(DEADLINE));
    assertThat(citedFindings.getValue())
        .singleElement()
        .satisfies(
            finding -> assertThat(finding.evidenceMessageGuids()).containsExactly("message-100"));
  }

  @Test
  void reductionMetadataSelectsACompleteMultiEvidenceFindingWithoutUnrelatedFindings() {
    service = service(2, 60_000, 5, 300_000);
    List<QuestionMessage> messages = messages(4, 10);
    exactMissThenChronological(messages);
    when(model.answer(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(1);
              return batch.getFirst().messageGuid().equals("message-0")
                  ? routed(
                      answered(
                          "Atlas ownership and readiness are confirmed.", "message-0", "message-1"))
                  : routed(answered("Beacon has a separate update.", "message-2", "message-3"));
            });
    when(model.reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> submitted = invocation.getArgument(1);
              RoutedModelAnswer routed =
                  routed(
                      answered("The Atlas update confirms both facts.", "message-0", "message-1"));
              return new RoutedReductionAnswer(routed, List.of(submitted.getFirst()));
            });

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.evidenceMessageCount()).isEqualTo(2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuestionFinding>> citedFindings = ArgumentCaptor.forClass(List.class);
    verify(model)
        .verifyReduction(
            eq(QUESTION),
            eq("The Atlas update confirms both facts."),
            citedFindings.capture(),
            eq(DEADLINE));
    assertThat(citedFindings.getValue())
        .singleElement()
        .satisfies(
            finding ->
                assertThat(finding.evidenceMessageGuids())
                    .containsExactly("message-0", "message-1"));
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
    stubReduction(routed(answered("The reduced complete answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.answer()).isEqualTo("The reduced complete answer.");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model).reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void chronologicalFallbackBatchesByCharacterCount() {
    List<QuestionMessage> messages = messages(3, 30_001);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    stubReduction(
        routed(
            answered(
                "The reduced character-bounded answer.", "message-0", "message-1", "message-2")));

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
    stubReduction(routed(answered("The supported partial result.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("model_batch_limit");
    assertThat(result.coverageThrough()).isEqualTo(messages.get(199).timestamp());
    assertThat(result.evidenceMessageCount()).isEqualTo(1);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void aggregateCharacterLimitReturnsBestSupportedPartialAnswer() {
    service = service(100, 60_000, 5, 110_000);
    List<QuestionMessage> messages = messages(3, 50_000);
    exactMissThenChronological(messages);
    answerEachBatchWithItsFirstEvidence();
    stubReduction(routed(answered("The supported partial result.", "message-0", "message-1")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    assertThat(result.coverageThrough()).isEqualTo(messages.get(0).timestamp());
    verify(model, times(1)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void exactFiveBatchLimitReturnsTheBestVerifiedFindingWithoutAnUnboundedReduction() {
    List<QuestionMessage> messages = messages(500, 10);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    stubReduction(
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
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("model_batch_limit");
    verify(model, times(5)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void rawTextAggregateEqualityIsPartialWhenSerializedPayloadExceedsTheLimit() {
    service = service(100, 60_000, 5, 110_000);
    List<QuestionMessage> messages = messages(2, 50_000);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    stubReduction(routed(answered("Both exact batches were reduced.", "message-0", "message-1")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("character_limit");
    verify(model, times(1)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model, never()).reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE));
  }

  @Test
  void oneNinetySecondDeadlineIsSharedAndStopsFallbackWork() {
    ArgumentCaptor<RetrievalRequest> exactRequest = ArgumentCaptor.forClass(RetrievalRequest.class);
    when(retriever.retrieveExact(exactRequest.capture(), eq(REPORT_PLAN)))
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
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(messages));
    answerEachBatchWithItsFirstEvidence();
    stubReduction(routed(answered("The reduced answer.", "message-0", "message-100")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    verify(model).plan(QUESTION, FROM, TO, DEADLINE);
    verify(model, times(2)).answer(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model, times(2)).verifyAnswer(eq(QUESTION), anyString(), anyList(), eq(DEADLINE));
    verify(model).reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE));
    verify(model).verifyReduction(eq(QUESTION), anyString(), anyList(), eq(DEADLINE));
  }

  @Test
  void generationCompletingAtTheDeadlineCannotBypassSupportVerification() {
    QuestionMessage score =
        message("score-guid", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofSeconds(90));
              return routed(answered("The only reported score is 4/6.", "score-guid"));
            });

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("time_limit");
    verify(retriever, never()).retrieveChronological(any());
  }

  @Test
  void sourceCoverageFailureIsNotHiddenByAModelRequestForMoreContext() {
    QuestionMessage score =
        message("score-guid", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of()));
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
                    List.of("score-guid"),
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
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
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
    verify(model, never()).reduceWithCitations(eq(QUESTION), anyList(), any());
  }

  @Test
  void totalSourceFailureReturnsUnavailableWithoutThrowing() {
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
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
  void lateExactFailureRetainsSupportedPartialAnswerAndRecordsWorkOnce() {
    QuestionMessage duplicate =
        message("duplicate", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    RetrievalResult partialExact =
        new RetrievalResult(
            List.of(duplicate),
            RetrievalMode.EXACT_SEARCH,
            CoverageStatus.PARTIAL,
            duplicate.timestamp(),
            "source_unavailable",
            4);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenThrow(
            new ConversationQuestionHistoryRetriever.PartialRetrievalException(
                partialExact, new IllegalStateException("late source failure")));
    when(retriever.retrieveChronological(any()))
        .thenThrow(new IllegalStateException("chronological source unavailable"));
    when(model.answer(QUESTION, List.of(duplicate), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "duplicate")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).isEqualTo("The only reported score is 4/6.");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    assertThat(result.evidenceMessageCount()).isEqualTo(1);
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.message.count").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.page.count").counter().count())
        .isEqualTo(4.0);
    verify(model, times(1)).answer(QUESTION, List.of(duplicate), DEADLINE);
  }

  @Test
  void lateChronologicalProcessingFailureSynthesizesPartialMessagesAndRecordsWorkOnce() {
    QuestionMessage completed =
        message("completed", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any()))
        .thenThrow(
            new ConversationQuestionHistoryRetriever.PartialRetrievalException(
                new RetrievalResult(
                    List.of(completed),
                    RetrievalMode.CHRONOLOGICAL,
                    CoverageStatus.PARTIAL,
                    completed.timestamp(),
                    "source_unavailable",
                    2),
                new IllegalStateException("late processing failure")));
    when(model.answer(QUESTION, List.of(completed), DEADLINE))
        .thenReturn(routed(answered("The only reported score is 4/6.", "completed")));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).isEqualTo("The only reported score is 4/6.");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    assertThat(result.evidenceMessageCount()).isEqualTo(1);
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.message.count").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("bbagent.memory.question.answer.page.count").counter().count())
        .isEqualTo(3.0);
    verify(model, times(1)).answer(QUESTION, List.of(completed), DEADLINE);
  }

  @Test
  void unsupportedEvidenceIsNotReportedAndDoesNotCauseAnotherFallbackLoop() {
    QuestionMessage exact =
        message("exact", "participant ending 0199", "Challenge round 1,877 result 4/6", 1);
    QuestionMessage chronological =
        message("chronological", "participant ending 0123", "Challenge round 1,877 result 3/6", 2);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(exact)));
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
    assertQuestionModelWork(2, 1, 0);
  }

  @Test
  void unsafeModelOutputCannotBecomeAGroupQuestionAnswer() {
    QuestionMessage score = message("score-guid", "Dom", "Challenge round 1,877 result 3/6", 1);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of(score)));
    when(model.answer(QUESTION, List.of(score), DEADLINE))
        .thenReturn(
            routed(
                answered(
                    "Dom reported challenge round 1,877 in 3/6; call +1 (555) 555-0199 for details.",
                    "score-guid")));
    when(retriever.retrieveChronological(any()))
        .thenThrow(new IllegalStateException("source unavailable"));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isNotEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).doesNotContain("555-0199", "challenge round 1,877");
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
  }

  @Test
  void crossMessageRaceAttributionCannotBypassTheGenericVerifier() {
    QuestionMessage dom = message("m-dom", "Dom", "I finished heat 7 in 52.4 seconds.", 1);
    QuestionMessage eve = message("m-eve", "Eve", "I finished heat 7 in 49.8 seconds.", 2);
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN)))
        .thenReturn(completeExact(List.of(dom, eve)));
    when(model.answer(QUESTION, List.of(dom, eve), DEADLINE))
        .thenReturn(
            routed(
                answered(
                    "Dom finished heat 7 in 49.8 seconds.", dom.messageGuid(), eve.messageGuid())));
    when(model.verifyAnswer(
            QUESTION, "Dom finished heat 7 in 49.8 seconds.", List.of(dom, eve), DEADLINE))
        .thenReturn(new RoutedSupportVerification(false, "openrouter/z-ai/glm-5.2", false));
    when(retriever.retrieveChronological(any()))
        .thenThrow(new IllegalStateException("source unavailable"));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isNotEqualTo(AnswerStatus.ANSWERED);
    assertThat(result.answer()).doesNotContain("Dom finished heat 7 in 49.8 seconds");
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
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
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertThat(
            registry
                .get("bbagent.memory.question.answer.count")
                .tag("outcome", "failure")
                .tag("failure_type", "unauthorized_range")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertQuestionModelWork(0, 0, 0);
  }

  @Test
  void unexpectedSourceFailureRecordsOneUnavailableOutcome() {
    when(store.findConversation(CONVERSATION_ID))
        .thenThrow(new IllegalStateException("database unavailable"));

    GroupQuestionAnswer result = service.answer(ACCOUNT, GROUP, QUESTION, FROM, TO);

    assertThat(result.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(totalQuestionAnswers()).isEqualTo(1.0);
    assertThat(
            registry
                .get("bbagent.memory.question.answer.count")
                .tag("outcome", "failure")
                .tag("failure_type", "source_unavailable")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertQuestionModelWork(0, 0, 0);
  }

  @Test
  void rejectsNonPositiveAndInternallyInconsistentLimits() {
    assertThatIllegalArgumentException().isThrownBy(() -> service(0, 60_000, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(100, 0, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(100, 60_000, 0, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(100, 60_000, 5, 59_999));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service(100, 60_000, 5, 300_000, Duration.ZERO));
  }

  private ConversationQuestionAnsweringService service(
      int maxBatchMessages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters) {
    return service(
        maxBatchMessages,
        maxBatchCharacters,
        maxModelBatches,
        maxAggregateCharacters,
        Duration.ofSeconds(90));
  }

  private ConversationQuestionAnsweringService service(
      int maxBatchMessages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters,
      Duration requestTimeout) {
    return new ConversationQuestionAnsweringService(
        store,
        retriever,
        model,
        metrics,
        maxBatchMessages,
        maxBatchCharacters,
        maxModelBatches,
        maxAggregateCharacters,
        requestTimeout,
        clock);
  }

  private double totalQuestionAnswers() {
    return registry.find("bbagent.memory.question.answer.count").counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private void assertQuestionModelWork(
      double expectedBatches, double expectedPlans, double expectedVerifications) {
    assertThat(registry.get("bbagent.memory.question.answer.model.batch.count").counter().count())
        .isEqualTo(expectedBatches);
    assertThat(registry.get("bbagent.memory.question.answer.plan.count").counter().count())
        .isEqualTo(expectedPlans);
    assertThat(registry.get("bbagent.memory.question.answer.verification.count").counter().count())
        .isEqualTo(expectedVerifications);
  }

  private void exactMissThenChronological(List<QuestionMessage> messages) {
    when(retriever.retrieveExact(any(), eq(REPORT_PLAN))).thenReturn(completeExact(List.of()));
    when(retriever.retrieveChronological(any())).thenReturn(completeChronological(messages));
  }

  private void answerEachBatchWithItsFirstEvidence() {
    when(model.answer(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(1);
              String evidenceGuid = batch.get(0).messageGuid();
              return routed(answered("A supported factual finding.", evidenceGuid));
            });
  }

  private void stubReduction(RoutedModelAnswer routed) {
    when(model.reduceWithCitations(eq(QUESTION), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> submitted = invocation.getArgument(1);
              Set<String> cited = Set.copyOf(routed.answer().evidenceMessageGuids());
              List<QuestionFinding> selected =
                  submitted.stream()
                      .filter(finding -> cited.containsAll(finding.evidenceMessageGuids()))
                      .toList();
              return new RoutedReductionAnswer(routed, selected);
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
              assertThat(payloadSizer.answerInputCharacters(QUESTION, batch))
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

  private static RoutedSupportVerification supportedVerification() {
    return new RoutedSupportVerification(true, "openrouter/z-ai/glm-5.2", false);
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
