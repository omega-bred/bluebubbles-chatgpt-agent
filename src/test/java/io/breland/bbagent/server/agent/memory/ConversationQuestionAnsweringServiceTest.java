package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.Confidence;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistorySource;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationQuestionAnsweringServiceTest {
  private static final String ACCOUNT = "account-1";
  private static final String CONVERSATION_ID = "conversation-1";
  private static final String QUESTION = "Who posted the latest update?";
  private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-10T17:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(90);
  private static final AuthorizedGroup GROUP =
      new AuthorizedGroup(CONVERSATION_ID, "Project Chat", NOW.minusSeconds(1));
  private static final ConversationRecord CONVERSATION =
      new ConversationRecord(
          CONVERSATION_ID,
          "bluebubbles",
          "iMessage;+;group",
          true,
          "Project Chat",
          FROM.minusSeconds(1),
          ACCOUNT,
          NOW);

  private final ConversationMemoryStore store = mock(ConversationMemoryStore.class);
  private final ConversationQuestionHistoryRetriever retriever =
      mock(ConversationQuestionHistoryRetriever.class);
  private final ConversationQuestionAnsweringModelClient model =
      mock(ConversationQuestionAnsweringModelClient.class);
  private final ConversationQuestionAnsweringModelClient payloadSizer =
      new ConversationQuestionAnsweringModelClient(
          mock(ConversationMemoryResponsesClient.class),
          new ObjectMapper().findAndRegisterModules());
  private final MutableClock clock = new MutableClock(NOW);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OperationalMetricsService metrics = new OperationalMetricsService(registry);
  private ConversationQuestionAnsweringService service;

  @BeforeEach
  void setUp() {
    service = service(500, 100, 60_000, 5, 300_000);
    when(store.findConversation(CONVERSATION_ID)).thenReturn(Optional.of(CONVERSATION));
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, Instant.EPOCH, NOW))
        .thenReturn(List.of(new MembershipInterval(FROM, null)));
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, FROM, NOW))
        .thenReturn(List.of(new MembershipInterval(FROM, null)));
    when(model.windowInputCharacters(anyString(), any(), nullable(String.class), anyList()))
        .thenAnswer(
            invocation ->
                payloadSizer.windowInputCharacters(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
    when(model.findingReductionInputCharacters(
            anyString(), any(), nullable(String.class), anyList(), any(Boolean.class)))
        .thenAnswer(
            invocation ->
                payloadSizer.findingReductionInputCharacters(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)));
  }

  @Test
  void answersFromTheFirstNewestWindowWithOneModelCall() {
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(answered("Sam posted the only update.", "m1", "Sam")));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Sam posted the only update.");
    assertThat(answer.clarificationQuestion()).isNull();
    assertQuestionMetric("answered", true);
    verify(model, times(1)).decide(anyString(), any(), nullable(String.class), anyList(), any());
    verify(model, never())
        .reduceFindings(
            anyString(), any(), nullable(String.class), anyList(), any(Boolean.class), any());
  }

  @Test
  void answersAcrossBatchesWhenCitationMetadataIsOmitted() {
    QuestionMessage first = message("m1", "Sam", "The first fact.", 1);
    QuestionMessage second = message("m2", "Lee", "The second fact.", 2);
    int splitBeforeTwoMessages =
        payloadSizer.windowInputCharacters(QUESTION, NOW, null, List.of(first, second)) - 1;
    service = service(500, 100, splitBeforeTwoMessages, 5, splitBeforeTwoMessages * 5);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(first, second), null, true));
    when(model.decide(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(DEADLINE)))
        .thenReturn(routed(answered("The batch contains a relevant fact.", List.of(), List.of())));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenReturn(
            routedReduction(
                answered("Sam and Lee supplied the relevant facts.", List.of(), List.of()),
                List.of()));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Sam and Lee supplied the relevant facts.");
    assertThat(answer.unresolvedParticipants()).isEmpty();
    verify(model, times(2)).decide(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(DEADLINE));
    verify(model)
        .reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE));
  }

  @Test
  void noRangeSearchesAllAuthorizedHistoryAndPropagatesTimezone() {
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, "America/Los_Angeles", List.of(message), DEADLINE))
        .thenReturn(routed(answered("Sam posted it today.", "m1", "Sam")));

    service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, "America/Los_Angeles");

    ArgumentCaptor<RetrievalRequest> request = ArgumentCaptor.forClass(RetrievalRequest.class);
    verify(retriever).retrieveWindow(request.capture(), isNull(), eq(500));
    assertThat(request.getValue().from()).isEqualTo(Instant.EPOCH);
    assertThat(request.getValue().to()).isEqualTo(NOW);
    assertThat(request.getValue().memberships())
        .containsExactly(new MembershipInterval(Instant.EPOCH, null));
    verify(store).findMembershipIntervals(CONVERSATION_ID, ACCOUNT, Instant.EPOCH, NOW);
  }

  @Test
  void nonEnablerRemainsLimitedToObservedMembershipIntervals() {
    ConversationRecord enabledByAnotherAccount =
        new ConversationRecord(
            CONVERSATION_ID,
            "bluebubbles",
            "iMessage;+;group",
            true,
            "Project Chat",
            FROM.minusSeconds(1),
            "account-2",
            NOW);
    when(store.findConversation(CONVERSATION_ID)).thenReturn(Optional.of(enabledByAnotherAccount));
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(answered("Sam posted it.", "m1", "Sam")));

    service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    ArgumentCaptor<RetrievalRequest> request = ArgumentCaptor.forClass(RetrievalRequest.class);
    verify(retriever).retrieveWindow(request.capture(), isNull(), eq(500));
    assertThat(request.getValue().memberships())
        .containsExactly(new MembershipInterval(FROM, null));
  }

  @Test
  void enablerWithoutActiveMembershipRemainsLimitedToObservedInterval() {
    MembershipInterval endedMembership = new MembershipInterval(FROM, NOW.minusSeconds(1));
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, Instant.EPOCH, NOW))
        .thenReturn(List.of(endedMembership));
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(answered("Sam posted it.", "m1", "Sam")));

    service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    ArgumentCaptor<RetrievalRequest> request = ArgumentCaptor.forClass(RetrievalRequest.class);
    verify(retriever).retrieveWindow(request.capture(), isNull(), eq(500));
    assertThat(request.getValue().memberships()).containsExactly(endedMembership);
  }

  @Test
  void explicitRangeRemainsAHardMembershipAndRetrievalBound() {
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(answered("Sam posted it.", "m1", "Sam")));

    service.answer(ACCOUNT, GROUP, QUESTION, FROM, NOW, null);

    ArgumentCaptor<RetrievalRequest> request = ArgumentCaptor.forClass(RetrievalRequest.class);
    verify(retriever).retrieveWindow(request.capture(), isNull(), eq(500));
    assertThat(request.getValue().from()).isEqualTo(FROM);
    verify(store).findMembershipIntervals(CONVERSATION_ID, ACCOUNT, FROM, NOW);
  }

  @Test
  void modelCanRequestTheImmediatelyOlderWindow() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recent = message("m1", "Sam", "That follows the earlier decision.", 2);
    QuestionMessage older = message("m0", "Lee", "The decision was Tuesday.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recent), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(recent), DEADLINE))
        .thenReturn(routed(needOlder(provisional("An earlier decision is referenced.", "m1"))));
    when(model.decide(QUESTION, NOW, null, List.of(older), DEADLINE))
        .thenReturn(routed(answered("The earlier decision was Tuesday.", "m0", "Lee")));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              return routedReduction(
                  answered(
                      "The earlier decision was Tuesday.", List.of("m0", "m1"), List.of("Lee")),
                  findings);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).contains("Tuesday");
    verify(retriever).retrieveWindow(any(), eq(cursor), eq(500));
  }

  @Test
  void uncitedProvisionalFindingStillSearchesTheImmediatelyOlderWindow() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recent = message("m1", "Sam", "That result was posted earlier.", 2);
    QuestionMessage older = message("m0", "Lee", "Lee and Sam tied for the win.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recent), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(recent), DEADLINE))
        .thenReturn(
            routed(
                needOlder(
                    new WindowFinding(
                        "The thread points to an older result.",
                        Confidence.MEDIUM,
                        List.of(),
                        List.of()))));
    when(model.decide(QUESTION, NOW, null, List.of(older), DEADLINE))
        .thenReturn(routed(answered("Lee and Sam tied.", "m0", "Lee")));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              List<QuestionFinding> cited =
                  findings.stream()
                      .filter(finding -> !finding.evidenceMessageGuids().isEmpty())
                      .toList();
              return routedReduction(
                  answered("Lee and Sam tied.", List.of("m0"), List.of("Lee")), cited);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Lee and Sam tied.");
    verify(retriever).retrieveWindow(any(), eq(cursor), eq(500));
  }

  @Test
  void noAnswerChecksTheImmediatelyOlderWindowBeforeStopping() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recent = message("m1", "Sam", "An unrelated recent update.", 2);
    QuestionMessage older = message("m0", "Lee", "Lee and Sam tied for the win.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recent), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(recent), DEADLINE))
        .thenReturn(routed(noAnswer("I couldn't find that in these messages.")));
    when(model.decide(QUESTION, NOW, null, List.of(older), DEADLINE))
        .thenReturn(routed(answered("Lee and Sam tied.", "m0", "Lee")));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Lee and Sam tied.");
    verify(retriever).retrieveWindow(any(), eq(cursor), eq(500));
  }

  @Test
  void reducedNoAnswerChecksTheImmediatelyOlderWindowBeforeStopping() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recentFirst = message("m2", "Sam", "x".repeat(5_000), 2);
    QuestionMessage recentSecond = message("m3", "Alex", "y".repeat(5_000), 3);
    QuestionMessage older = message("m1", "Lee", "z".repeat(5_000), 1);
    service = service(500, 100, 8_000, 5, 40_000);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recentFirst, recentSecond), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    when(model.decide(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> messages = invocation.getArgument(3);
              QuestionMessage message = messages.getFirst();
              return routed(
                  answered(
                      message.participant() + " supplied a fact.",
                      message.messageGuid(),
                      message.participant()));
            });
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(true), eq(DEADLINE)))
        .thenReturn(
            routedReduction(noAnswer("I couldn't find that in these messages."), List.of()));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              return routedReduction(
                  answered("Lee and Sam tied.", List.of("m1", "m2", "m3"), List.of("Lee", "Sam")),
                  findings);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Lee and Sam tied.");
    verify(retriever).retrieveWindow(any(), eq(cursor), eq(500));
    verify(model)
        .reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(true), eq(DEADLINE));
  }

  @Test
  void returnsNaturalClarificationWithoutFetchingOlderWindow() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage message = message("m1", "Sam", "I remember that happening.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), cursor, false));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(clarification("About when did that happen?")));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
    assertThat(answer.answer()).isNull();
    assertThat(answer.clarificationQuestion()).isEqualTo("About when did that happen?");
    assertQuestionMetric("clarification_required", true);
    verify(retriever, never()).retrieveWindow(any(), eq(cursor), anyInt());
  }

  @Test
  void exhaustedNoAnswerUsesNaturalModelCopy() {
    QuestionMessage message = message("m1", "Sam", "An unrelated update.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(routed(noAnswer("I couldn't find that in this group's messages.")));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.NO_ANSWER);
    assertThat(answer.answer()).isEqualTo("I couldn't find that in this group's messages.");
    assertThat(answer.answer()).doesNotContain("authorized", "coverage", "evidence");
    assertQuestionMetric("no_answer", true);
  }

  @Test
  void emptyExhaustedHistoryDoesNotCallTheModel() {
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(), null, true));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.NO_ANSWER);
    assertThat(answer.answer()).contains("couldn't find any group messages");
    verify(model, never()).decide(anyString(), any(), nullable(String.class), anyList(), any());
  }

  @Test
  void multiWindowReductionUsesOnlyCitedFindingsAndHints() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recent =
        hintedMessage("recent", "participant ending 0199", "+15555550199", "Recent clue", 3);
    QuestionMessage uncited =
        hintedMessage("uncited", "participant ending 0300", "+15555550300", "Unrelated clue", 4);
    QuestionMessage older =
        hintedMessage("older", "participant ending 0200", "+15555550200", "Older fact", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recent, uncited), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    ModelWindowDecision firstDecision =
        new ModelWindowDecision(
            WindowAction.NEED_OLDER_MESSAGES,
            null,
            null,
            Confidence.MEDIUM,
            List.of(),
            List.of(
                new WindowFinding(
                    "The recent clue points backward.",
                    Confidence.MEDIUM,
                    List.of("recent"),
                    List.of("participant ending 0199")),
                new WindowFinding(
                    "This clue is unrelated.",
                    Confidence.LOW,
                    List.of("uncited"),
                    List.of("participant ending 0300"))),
            List.of());
    when(model.decide(QUESTION, NOW, null, List.of(recent, uncited), DEADLINE))
        .thenReturn(routed(firstDecision));
    when(model.decide(QUESTION, NOW, null, List.of(older), DEADLINE))
        .thenReturn(
            routed(
                answered(
                    "The older fact completes the answer.", "older", "participant ending 0200")));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              List<QuestionFinding> cited =
                  findings.stream()
                      .filter(finding -> !finding.evidenceMessageGuids().contains("uncited"))
                      .toList();
              return routedReduction(
                  answered(
                      "The two cited participants supplied the answer.",
                      List.of("older", "recent"),
                      List.of("participant ending 0200", "participant ending 0199")),
                  cited);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.unresolvedParticipants())
        .extracting(ParticipantHint::normalizedIdentity)
        .containsExactlyInAnyOrder("+15555550199", "+15555550200")
        .doesNotContain("+15555550300");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuestionFinding>> findings = ArgumentCaptor.forClass(List.class);
    verify(model)
        .reduceFindings(
            eq(QUESTION), eq(NOW), isNull(), findings.capture(), eq(false), eq(DEADLINE));
    assertThat(findings.getValue()).hasSize(3);
  }

  @Test
  void oversizedWindowIsSplitIntoContiguousSubchunksThenReduced() {
    QuestionMessage first = message("m1", "Sam", "a".repeat(500), 1);
    QuestionMessage second = message("m2", "Lee", "b".repeat(500), 2);
    QuestionMessage third = message("m3", "Alex", "c".repeat(500), 3);
    int oneMessageCharacters =
        List.of(first, second, third).stream()
            .mapToInt(
                message ->
                    payloadSizer.windowInputCharacters(QUESTION, NOW, null, List.of(message)))
            .max()
            .orElseThrow();
    service = service(500, 100, oneMessageCharacters, 5, oneMessageCharacters * 5);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(first, second, third), null, true));
    when(model.decide(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionMessage> batch = invocation.getArgument(3);
              QuestionMessage only = batch.getFirst();
              return routed(
                  answered(
                      only.participant() + " supplied a fact.",
                      only.messageGuid(),
                      only.participant()));
            });
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              return routedReduction(
                  answered(
                      "Sam, Lee, and Alex supplied the facts.",
                      List.of("m1", "m2", "m3"),
                      List.of("Sam", "Lee", "Alex")),
                  findings);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuestionMessage>> chunks = ArgumentCaptor.forClass(List.class);
    verify(model, times(3)).decide(eq(QUESTION), eq(NOW), isNull(), chunks.capture(), eq(DEADLINE));
    assertThat(chunks.getAllValues())
        .containsExactly(List.of(first), List.of(second), List.of(third));
    verify(model)
        .reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE));
  }

  @Test
  void aSingleMessageTooLargeForTheModelAsksForANarrowerTime() {
    QuestionMessage large = message("large", "Sam", "x".repeat(5_000), 1);
    int tooSmall = payloadSizer.windowInputCharacters(QUESTION, NOW, null, List.of(large)) - 1;
    service = service(500, 100, tooSmall, 5, 300_000);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(large), null, true));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
    assertThat(answer.clarificationQuestion()).isEqualTo("About when should I look?");
    verify(model, never()).decide(anyString(), any(), nullable(String.class), anyList(), any());
  }

  @Test
  void finalRequestWideValidationBlocksMessageGuidLeakage() {
    HistoryWindowCursor cursor =
        new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 500, null, null);
    QuestionMessage recent = message("recent-guid", "Sam", "Look earlier.", 2);
    QuestionMessage older = message("older-guid", "Lee", "The answer is here.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(recent), cursor, false));
    when(retriever.retrieveWindow(any(), eq(cursor), eq(500)))
        .thenReturn(window(List.of(older), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(recent), DEADLINE))
        .thenReturn(routed(needOlder(provisional("Look earlier.", "recent-guid"))));
    when(model.decide(QUESTION, NOW, null, List.of(older), DEADLINE))
        .thenReturn(routed(answered("Lee supplied the fact.", "older-guid", "Lee")));
    when(model.reduceFindings(eq(QUESTION), eq(NOW), isNull(), anyList(), eq(false), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              List<QuestionFinding> findings = invocation.getArgument(3);
              return routedReduction(
                  answered(
                      "The answer leaks older-guid.",
                      List.of("older-guid", "recent-guid"),
                      List.of("Lee")),
                  findings);
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(answer.answer()).doesNotContain("older-guid", "authorized", "coverage");
  }

  @Test
  void answerReturnedAtTheDeadlineIsDiscarded() {
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(window(List.of(message), null, true));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenAnswer(
            ignored -> {
              clock.set(DEADLINE);
              return routed(answered("Sam posted it.", "m1", "Sam"));
            });

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(answer.answer()).doesNotContain("Sam posted it");
  }

  @Test
  void sourceFailureReturnsNaturalUnavailableCopy() {
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenThrow(new IllegalStateException("source failed"));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    assertThat(answer.answer())
        .contains("couldn't search")
        .doesNotContain("authorized", "coverage", "evidence", "source_unavailable");
    assertQuestionMetric("unavailable", false);
  }

  @Test
  void supportedAnswerFromPartialSourceHistoryIsStillReturned() {
    QuestionMessage message = message("m1", "Sam", "The update is ready.", 1);
    when(retriever.retrieveWindow(any(), isNull(), eq(500)))
        .thenReturn(
            new HistoryWindow(List.of(message), null, true, false, "source_unavailable", 1));
    when(model.decide(QUESTION, NOW, null, List.of(message), DEADLINE))
        .thenReturn(
            new RoutedWindowDecision(
                answered("Sam posted the available update.", "m1", "Sam"),
                "openai/gpt-4.1-mini",
                true));

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
    assertThat(answer.answer()).isEqualTo("Sam posted the available update.");
    assertThat(answer.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(answer.fallbackUsed()).isTrue();
  }

  @Test
  void missingMembershipDoesNotTouchHistoryOrModel() {
    when(store.findMembershipIntervals(CONVERSATION_ID, ACCOUNT, Instant.EPOCH, NOW))
        .thenReturn(List.of());

    GroupQuestionAnswer answer = service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, null);

    assertThat(answer.status()).isEqualTo(AnswerStatus.UNAVAILABLE);
    verifyNoInteractions(retriever);
    verify(model, never()).decide(anyString(), any(), nullable(String.class), anyList(), any());
  }

  @Test
  void validatesConstructorAndRequestLimits() {
    assertThatIllegalArgumentException().isThrownBy(() -> service(0, 100, 60_000, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(501, 100, 60_000, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(500, 0, 60_000, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(500, 100, 60_001, 5, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(500, 100, 60_000, 6, 300_000));
    assertThatIllegalArgumentException().isThrownBy(() -> service(500, 100, 60_000, 5, 59_999));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ConversationQuestionAnsweringService(
                    store,
                    retriever,
                    model,
                    metrics,
                    500,
                    100,
                    60_000,
                    5,
                    300_000,
                    Duration.ofSeconds(91),
                    clock));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.answer(" ", GROUP, QUESTION, null, NOW, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.answer(ACCOUNT, GROUP, " ", null, NOW, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.answer(ACCOUNT, GROUP, QUESTION, NOW, NOW, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.answer(ACCOUNT, GROUP, QUESTION, null, NOW, "Not/AZone"));
    verifyNoMoreInteractions(retriever);
  }

  private ConversationQuestionAnsweringService service(
      int windowMessages,
      int maxHistoryPages,
      int maxBatchCharacters,
      int maxModelBatches,
      int maxAggregateCharacters) {
    return new ConversationQuestionAnsweringService(
        store,
        retriever,
        model,
        metrics,
        windowMessages,
        maxHistoryPages,
        maxBatchCharacters,
        maxModelBatches,
        maxAggregateCharacters,
        Duration.ofSeconds(90),
        clock);
  }

  private HistoryWindow window(
      List<QuestionMessage> messages, HistoryWindowCursor nextCursor, boolean exhausted) {
    return new HistoryWindow(messages, nextCursor, exhausted, true, null, 1);
  }

  private void assertQuestionMetric(String action, boolean success) {
    assertThat(
            registry
                .get("bbagent.memory.question.answer.count")
                .tag("action", action)
                .tag("outcome", success ? "success" : "failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry.getMeters().stream()
                .filter(
                    meter -> meter.getId().getName().startsWith("bbagent.memory.question.answer"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue()))
        .noneMatch(
            value ->
                value.contains(QUESTION)
                    || value.contains(CONVERSATION_ID)
                    || value.contains(ACCOUNT));
  }

  private RoutedWindowDecision routed(ModelWindowDecision decision) {
    return new RoutedWindowDecision(decision, "openrouter/z-ai/glm-5.2", false);
  }

  private RoutedFindingReduction routedReduction(
      ModelWindowDecision decision, List<QuestionFinding> findings) {
    return new RoutedFindingReduction(decision, findings, "openrouter/z-ai/glm-5.2", false);
  }

  private ModelWindowDecision answered(String answer, String evidenceGuid, String participant) {
    return answered(answer, List.of(evidenceGuid), List.of(participant));
  }

  private ModelWindowDecision answered(
      String answer, List<String> evidenceGuids, List<String> participants) {
    return new ModelWindowDecision(
        WindowAction.ANSWERED,
        answer,
        null,
        Confidence.HIGH,
        evidenceGuids,
        List.of(),
        participants);
  }

  private ModelWindowDecision needOlder(WindowFinding finding) {
    return new ModelWindowDecision(
        WindowAction.NEED_OLDER_MESSAGES,
        null,
        null,
        Confidence.MEDIUM,
        List.of(),
        List.of(finding),
        List.of());
  }

  private WindowFinding provisional(String answer, String evidenceGuid) {
    return new WindowFinding(answer, Confidence.MEDIUM, List.of(evidenceGuid), List.of());
  }

  private ModelWindowDecision clarification(String question) {
    return new ModelWindowDecision(
        WindowAction.NEED_TIME_CLARIFICATION,
        null,
        question,
        Confidence.LOW,
        List.of(),
        List.of(),
        List.of());
  }

  private ModelWindowDecision noAnswer(String answer) {
    return new ModelWindowDecision(
        WindowAction.NO_ANSWER, answer, null, Confidence.LOW, List.of(), List.of(), List.of());
  }

  private QuestionMessage message(
      String guid, String participant, String text, long chronologicalPosition) {
    return new QuestionMessage(
        guid, participant, NOW.minusSeconds(1_000 - chronologicalPosition), text);
  }

  private QuestionMessage hintedMessage(
      String guid, String participant, String identity, String text, long chronologicalPosition) {
    return new QuestionMessage(
        guid,
        participant,
        NOW.minusSeconds(1_000 - chronologicalPosition),
        text,
        new ParticipantHint(participant, identity));
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant instant) {
      this.instant = instant;
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
