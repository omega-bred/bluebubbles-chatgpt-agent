package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.generated.bluebubblesclient.model.MessageHandle;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.CoverageStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.MembershipInterval;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationQuestionHistoryRetrieverTest {
  private static final String ACCOUNT_ID = "account-1";
  private static final String CONVERSATION_ID = "conversation-1";
  private static final String GUID = "iMessage;+;group";
  private static final Instant FROM = Instant.parse("2026-08-09T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T14:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-09T12:30:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-08-09T13:00:00Z");
  private static final UUID HIT_GUID = UUID.fromString("00000000-0000-0000-0000-000000000101");

  private final BBHttpClientWrapper bb = Mockito.mock(BBHttpClientWrapper.class);
  private final ConversationMemoryStore store = Mockito.mock(ConversationMemoryStore.class);
  private final AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
  private final ConversationHistoryMessageMapper mapper =
      Mockito.spy(new ConversationHistoryMessageMapper(accountResolver));
  private ConversationQuestionHistoryRetriever retriever;

  @BeforeEach
  void setUp() {
    when(bb.searchConversationHistoryForQuestion(
            anyString(), anyString(), any(), any(), anyInt(), anyInt(), any(Duration.class)))
        .thenAnswer(
            invocation ->
                bb.searchConversationHistory(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5)));
    when(bb.getMessagesInChatForQuestion(
            anyString(), any(), any(), anyInt(), anyInt(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation ->
                bb.getMessagesInChat(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5)));
    retriever = retriever(5, 500, 100, 3, 300_000, NOW);
  }

  @Test
  void searchesEveryLiteralTermAndAddsAuthorizedNeighborContext() {
    Message hit = searchMessage(HIT_GUID, "Wordle 1,877 4/6", at("12:00"));
    Message duplicateHit = searchMessage(HIT_GUID, "Wordle 1,877 4/6", at("12:00"));
    when(bb.searchConversationHistory(GUID, "Wordle", at("11:00"), TO, 500, 0))
        .thenReturn(List.of(hit));
    when(bb.searchConversationHistory(GUID, "1,877", at("11:00"), TO, 500, 0))
        .thenReturn(List.of(duplicateHit));
    when(bb.getMessagesInChat(GUID, at("11:00"), at("12:00"), 0, 4, "DESC"))
        .thenReturn(
            List.of(
                raw("before", "good luck", at("11:59")),
                raw(HIT_GUID.toString(), "Wordle 1,877 4/6", at("12:00")),
                raw("unauthorized", "older secret", at("10:30"))));
    when(bb.getMessagesInChat(GUID, at("12:00"), TO, 0, 4, "ASC"))
        .thenReturn(
            List.of(
                raw(HIT_GUID.toString(), "Wordle 1,877 4/6", at("12:00")),
                raw("after", "nice", at("12:01"))));

    RetrievalResult result =
        retriever.retrieveExact(request(activeFrom("11:00")), plan("Wordle", "1,877"));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("before", HIT_GUID.toString(), "after");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.pageCount()).isEqualTo(4);
  }

  @Test
  void pagesEachLiteralByFiveHundredUntilAShortPage() {
    Message hit = searchMessage(HIT_GUID, "Wordle", at("12:00"));
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(Collections.nCopies(500, hit));
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 500)).thenReturn(List.of());
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.pageCount()).isEqualTo(4);
    verify(bb).searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 500);
  }

  @Test
  void sharesTheRequestBudgetWithNeighborCalls() {
    retriever = retriever(5, 500, 2, 3, 300_000, NOW);
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(List.of(searchMessage(HIT_GUID, "Wordle", at("12:00"))));
    when(bb.getMessagesInChat(GUID, FROM, at("12:00"), 0, 4, "DESC"))
        .thenReturn(List.of(raw("before", "good luck", at("11:59"))));

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.pageCount()).isEqualTo(2);
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("history_limit");
    verify(bb, never()).getMessagesInChat(GUID, at("12:00"), TO, 0, 4, "ASC");
  }

  @Test
  void stopsAtTheDeadlineBeforeCallingAnySource() {
    retriever = retriever(5, 500, 100, 3, 300_000, DEADLINE.plusSeconds(1));

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("time_limit");
    assertThat(result.pageCount()).isZero();
    verifyNoInteractions(bb, store);
  }

  @Test
  void enforcesAggregateCharacterAndFiveThousandCandidateLimits() {
    retriever = retriever(5, 500, 100, 1, 5, NOW);
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(
            List.of(
                searchMessage(HIT_GUID, "12345", at("12:00")),
                searchMessage(
                    UUID.fromString("00000000-0000-0000-0000-000000000102"), "6", at("12:01"))));
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());

    RetrievalResult characterLimited =
        retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(characterLimited.messages()).hasSize(1);
    assertThat(characterLimited.partialReason()).isEqualTo("history_limit");

    retriever = retriever(5, 500, 100, 1, 300_000, NOW);
    Message hit = searchMessage(HIT_GUID, "score", at("12:00"));
    when(bb.searchConversationHistory(GUID, "scores", FROM, TO, 500, 0)).thenReturn(List.of(hit));
    when(bb.getMessagesInChat(GUID, FROM, at("12:00"), 0, 2, "DESC"))
        .thenReturn(historyPage(5_000));

    RetrievalResult candidateLimited =
        retriever.retrieveExact(request(activeFrom("10:00")), plan("scores"));

    assertThat(candidateLimited.messages()).hasSize(5_000);
    assertThat(candidateLimited.partialReason()).isEqualTo("history_limit");
    assertThat(candidateLimited.pageCount()).isEqualTo(2);
  }

  @Test
  void plannerHintsCanOnlyNarrowBoundsAndSenderHintNeverFiltersEvidence() {
    Instant narrowedTo = at("13:00");
    UUID domGuid = UUID.fromString("00000000-0000-0000-0000-000000000103");
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, narrowedTo, 500, 0))
        .thenReturn(
            List.of(
                searchMessage(HIT_GUID, "first", at("11:30")),
                searchMessage(domGuid, "preferred", at("12:00"))));
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());

    SearchPlan plan =
        new SearchPlan(List.of("Wordle"), "+15555550199", FROM.minusSeconds(60), narrowedTo);
    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan);

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly(HIT_GUID.toString(), domGuid.toString());
    verify(bb).searchConversationHistory(GUID, "Wordle", FROM, narrowedTo, 500, 0);
    verify(store, never()).findMessages(anyString(), any(), any());
  }

  @Test
  void senderHintNeverOverridesChronologicalOrderingOrFiltersEvidence() {
    UUID domGuid = UUID.fromString("00000000-0000-0000-0000-000000000103");
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(
            List.of(
                searchMessage(HIT_GUID, "first", at("11:30")),
                searchMessage(domGuid, "preferred", at("12:00"))));
    when(bb.getMessagesInChat(GUID, FROM, at("11:30"), 0, 4, "DESC"))
        .thenReturn(List.of(raw(HIT_GUID.toString(), "first", at("11:30"), "+15555550199")));
    when(bb.getMessagesInChat(GUID, at("11:30"), TO, 0, 4, "ASC")).thenReturn(List.of());
    when(bb.getMessagesInChat(GUID, FROM, at("12:00"), 0, 4, "DESC"))
        .thenReturn(List.of(raw(domGuid.toString(), "preferred", at("12:00"), "+15555550200")));
    when(bb.getMessagesInChat(GUID, at("12:00"), TO, 0, 4, "ASC")).thenReturn(List.of());
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenAnswer(
            invocation -> {
              IncomingMessage incoming = invocation.getArgument(0);
              if ("+15555550199".equals(incoming.sender())) {
                return Optional.of(TestAccounts.resolved("account-alice", "Alice"));
              }
              if ("+15555550200".equals(incoming.sender())) {
                return Optional.of(TestAccounts.resolved("account-dom", "Dom"));
              }
              return Optional.empty();
            });

    RetrievalResult result =
        retriever.retrieveExact(
            request(activeFrom("10:00")), new SearchPlan(List.of("Wordle"), "dom", null, null));

    assertThat(result.messages())
        .extracting(QuestionMessage::participant)
        .containsExactly("Alice", "Dom");
    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly(HIT_GUID.toString(), domGuid.toString());
  }

  @Test
  void senderHintOnlyTiebreaksEqualTimestampsUsingSafeLabels() {
    retriever = retriever(5, 500, 100, 1, 300_000, NOW);
    UUID domGuid = UUID.fromString("00000000-0000-0000-0000-000000000103");
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(
            List.of(
                searchMessage(HIT_GUID, "first", at("12:00"), "+15555550199"),
                searchMessage(domGuid, "preferred", at("12:00"), "+15555550200")));
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenAnswer(
            invocation -> {
              IncomingMessage incoming = invocation.getArgument(0);
              return Optional.of(
                  "+15555550200".equals(incoming.sender())
                      ? TestAccounts.resolved("account-dom", "Dom")
                      : TestAccounts.resolved("account-alice", "Alice"));
            });
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());

    RetrievalResult result =
        retriever.retrieveExact(
            request(activeFrom("10:00")), new SearchPlan(List.of("Wordle"), "dom", null, null));

    assertThat(result.messages())
        .extracting(QuestionMessage::participant)
        .containsExactly("Dom", "Alice");
  }

  @Test
  void directExactHitPreservesHandleForSafeMappingWithoutNeighborDuplicate() {
    retriever = retriever(5, 500, 100, 1, 300_000, NOW);
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(List.of(searchMessage(HIT_GUID, "Wordle", at("12:00"), "+15555550200")));
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(TestAccounts.resolved("account-dom", "Dom")));
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.messages()).extracting(QuestionMessage::participant).containsExactly("Dom");
    assertThat(result.pageCount()).isEqualTo(3);
  }

  @Test
  void nonBlueBubblesExactRetrievalUsesAuthorizedJournalOnly() {
    when(store.findMessages(CONVERSATION_ID, FROM, TO.minusNanos(1)))
        .thenReturn(
            List.of(
                journal("matching", "account-2", "Wordle 1,877 3/6", at("12:00")),
                journal("other", "account-2", "Dinner is at seven", at("12:01"))));

    RetrievalResult result =
        retriever.retrieveExact(request("lxmf", activeFrom("10:00")), plan("wordle"));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("matching");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    verifyNoInteractions(bb);
  }

  @Test
  void nonBlueBubblesChronologicalRetrievalUsesAuthorizedJournalOnly() {
    when(store.findMessages(CONVERSATION_ID, FROM, TO.minusNanos(1)))
        .thenReturn(List.of(journal("journal", "account-2", "available", at("12:00"))));

    RetrievalResult result =
        retriever.retrieveChronological(request("future-transport", activeFrom("10:00")));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    verifyNoInteractions(bb);
  }

  @Test
  void exactRetrievalRejectsReturnedMessageFromAnotherChatBeforeMapping() {
    Message mismatched =
        searchMessage(HIT_GUID, "Wordle 1,877 3/6", at("12:00"))
            .chats(List.of(new Chat().guid("other-chat")));
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(List.of(mismatched));

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.messages()).isEmpty();
    verifyNoInteractions(accountResolver);
    verify(bb, never())
        .getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString());
  }

  @Test
  void chronologicalRetrievalRejectsReturnedMessageFromAnotherChatBeforeMapping() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner mismatched =
        raw("other-chat-message", "private", at("12:00"))
            .chats(
                List.of(
                    new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner()
                        .guid("other-chat")));
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC")).thenReturn(List.of(mismatched));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.messages()).isEmpty();
    verifyNoInteractions(accountResolver);
  }

  @Test
  void rejectsInvalidSearchPlansWithoutSourceAccess() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                retriever.retrieveExact(
                    request(activeFrom("10:00")),
                    new SearchPlan(List.of("Wordle", " "), null, null, null)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                retriever.retrieveExact(
                    request(activeFrom("10:00")), plan("1", "2", "3", "4", "5", "6")));
    verifyNoInteractions(bb, store);
  }

  @Test
  void chronologicallyPagesAndFiltersEveryCandidateByMembership() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> firstPage =
        new ArrayList<>(Collections.nCopies(500, raw("duplicate", "first", at("11:30"))));
    when(bb.getMessagesInChat(GUID, at("11:00"), at("13:00"), 0, 500, "ASC")).thenReturn(firstPage);
    when(bb.getMessagesInChat(GUID, at("11:00"), at("13:00"), 500, 500, "ASC"))
        .thenReturn(
            List.of(raw("later", "second", at("12:00")), raw("outside", "secret", at("13:30"))));

    RetrievalResult result =
        retriever.retrieveChronological(request(new MembershipInterval(at("11:00"), at("13:00"))));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("duplicate", "later");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.coverageThrough()).isEqualTo(TO);
    assertThat(result.pageCount()).isEqualTo(2);
  }

  @Test
  void deduplicatesRawGuidsBeforeMapping() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner duplicate =
        raw("duplicate", "available", at("11:30"));
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenReturn(List.of(duplicate, duplicate));

    RetrievalRequest request = request(activeFrom("10:00"));
    RetrievalResult result = retriever.retrieveChronological(request);

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("duplicate");
    verify(mapper, times(1)).fromBlueBubbles(any(), eq(ACCOUNT_ID), eq(request.mappingSession()));
  }

  @Test
  void cachesNormalizedBlueBubblesIdentityResolutionWithinOneRequest() {
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenReturn(
            List.of(
                raw("first", "first", at("11:30"), "+1 (555) 555-0199"),
                raw("second", "second", at("11:31"), "+15555550199")));
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(TestAccounts.resolved("account-dom", "Dom")));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.messages())
        .extracting(QuestionMessage::participant)
        .containsExactly("Dom", "Dom");
    verify(accountResolver, times(1)).resolve(any(IncomingMessage.class));
  }

  @Test
  void cachesJournalAccountResolutionWithinOneRequest() {
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenThrow(new IllegalStateException("unavailable"));
    when(store.findMessages(CONVERSATION_ID, FROM, TO.minusNanos(1)))
        .thenReturn(
            List.of(
                journal("first", "account-2", "first", at("11:30")),
                journal("second", "account-2", "second", at("11:31"))));
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(TestAccounts.resolved("account-2", "Dom")));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.messages())
        .extracting(QuestionMessage::participant)
        .containsExactly("Dom", "Dom");
    verify(accountResolver, times(1)).resolveById("account-2");
  }

  @Test
  void fallsBackToJournalOnlyWhenBlueBubblesFails() {
    when(bb.getMessagesInChat(GUID, at("11:00"), at("13:00"), 0, 500, "ASC"))
        .thenThrow(new IllegalStateException("unavailable"));
    when(store.findMessages(CONVERSATION_ID, at("11:00"), at("13:00").minusNanos(1)))
        .thenReturn(
            List.of(
                journal("m-1", "account-2", "available", at("12:00")),
                journal("m-2", "account-2", "outside", at("13:30"))));

    RetrievalResult result =
        retriever.retrieveChronological(request(new MembershipInterval(at("11:00"), at("13:00"))));

    assertThat(result.messages()).extracting(QuestionMessage::messageGuid).containsExactly("m-1");
    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
    assertThat(result.coverageThrough()).isEqualTo(at("12:00"));
    assertThat(result.pageCount()).isEqualTo(2);
  }

  @Test
  void neverExceedsOneHundredSourceCallsIncludingJournalFallback() {
    retriever = retriever(5, 500, 100, 1, 300_000, NOW);
    ApiV1ChatChatGuidMessageGet200ResponseDataInner duplicate =
        raw("duplicate", "available", at("11:30"));
    for (int page = 0; page < 99; page++) {
      when(bb.getMessagesInChat(GUID, FROM, TO, page * 500, 500, "ASC"))
          .thenReturn(Collections.nCopies(500, duplicate));
    }
    when(bb.getMessagesInChat(GUID, FROM, TO, 99 * 500, 500, "ASC"))
        .thenThrow(new IllegalStateException("unavailable"));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.pageCount()).isEqualTo(100);
    assertThat(result.partialReason()).isEqualTo("history_limit");
    verifyNoInteractions(store);
  }

  @Test
  void rejectsConfiguredSourceCallLimitAboveHardMaximum() {
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 500, 101, 1, 300_000, NOW));
  }

  @Test
  void rejectsNonPositiveCountsAndPageSizesAboveBlueBubblesMaximum() {
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(0, 500, 100, 3, 300_000, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 0, 100, 3, 300_000, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 501, 100, 3, 300_000, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 500, 0, 3, 300_000, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 500, 100, 0, 300_000, NOW));
    assertThatIllegalArgumentException().isThrownBy(() -> retriever(5, 500, 100, 3, 0, NOW));
  }

  @Test
  void lateExactSourceFailureCarriesCompletedPagesAndMessages() {
    retriever = retriever(5, 500, 100, 1, 300_000, NOW);
    Message hit = searchMessage(HIT_GUID, "Wordle", at("12:00"));
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(Collections.nCopies(500, hit));
    when(bb.getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(List.of());
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 500))
        .thenThrow(new IllegalStateException("late source failure"));

    assertThatThrownBy(() -> retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle")))
        .isInstanceOf(ConversationQuestionHistoryRetriever.PartialRetrievalException.class)
        .satisfies(
            failure -> {
              ConversationQuestionHistoryRetriever.PartialRetrievalException partialFailure =
                  (ConversationQuestionHistoryRetriever.PartialRetrievalException) failure;
              assertThat(partialFailure.partialResult().messages())
                  .extracting(QuestionMessage::messageGuid)
                  .containsExactly(HIT_GUID.toString());
              assertThat(partialFailure.partialResult().pageCount()).isEqualTo(4);
              assertThat(partialFailure.partialResult().partialReason())
                  .isEqualTo("source_unavailable");
            });
  }

  @Test
  void lateChronologicalProcessingFailureCarriesCompletedPagesAndMessages() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner first = raw("first", "available", at("11:00"));
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenReturn(Collections.nCopies(500, first));
    when(bb.getMessagesInChat(GUID, FROM, TO, 500, 500, "ASC"))
        .thenReturn(List.of(raw("failing", "unmappable", at("12:00"), "+15555550999")));
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenAnswer(
            invocation -> {
              IncomingMessage incoming = invocation.getArgument(0);
              if ("+15555550999".equals(incoming.sender())) {
                throw new IllegalStateException("mapping failed");
              }
              return Optional.empty();
            });

    assertThatThrownBy(() -> retriever.retrieveChronological(request(activeFrom("10:00"))))
        .isInstanceOf(ConversationQuestionHistoryRetriever.PartialRetrievalException.class)
        .satisfies(
            failure -> {
              ConversationQuestionHistoryRetriever.PartialRetrievalException partialFailure =
                  (ConversationQuestionHistoryRetriever.PartialRetrievalException) failure;
              assertThat(partialFailure.partialResult().messages())
                  .extracting(QuestionMessage::messageGuid)
                  .containsExactly("first");
              assertThat(partialFailure.partialResult().pageCount()).isEqualTo(2);
              assertThat(partialFailure.partialResult().partialReason())
                  .isEqualTo("source_unavailable");
            });
  }

  @Test
  void checksAdvancingDeadlineBeforeEveryNeighborCall() {
    retriever =
        retriever(
            5, 500, 100, 3, 300_000, new AdvancingClock(List.of(NOW, DEADLINE), ZoneOffset.UTC));
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0))
        .thenReturn(List.of(searchMessage(HIT_GUID, "Wordle", at("12:00"))));

    RetrievalResult result = retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    assertThat(result.pageCount()).isEqualTo(1);
    assertThat(result.partialReason()).isEqualTo("time_limit");
    verify(bb, never())
        .getMessagesInChat(anyString(), any(), any(), anyInt(), anyInt(), anyString());
  }

  @Test
  void pagesOnlyClippedAuthorizedIntervalsAcrossMembershipGaps() {
    Instant firstEnd = at("10:30");
    Instant secondStart = at("13:30");
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, firstEnd, 500, 0))
        .thenReturn(List.of());
    when(bb.searchConversationHistory(GUID, "Wordle", secondStart, TO, 500, 0))
        .thenReturn(List.of());

    RetrievalResult result =
        retriever.retrieveExact(
            request(
                new MembershipInterval(FROM, firstEnd), new MembershipInterval(secondStart, TO)),
            plan("Wordle"));

    assertThat(result.coverageStatus()).isEqualTo(CoverageStatus.COMPLETE);
    assertThat(result.pageCount()).isEqualTo(2);
    verify(bb, never()).searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0);
  }

  @Test
  void chronologicalPagingResetsInsideEachAuthorizedInterval() {
    Instant firstEnd = at("10:30");
    Instant secondStart = at("13:30");
    when(bb.getMessagesInChat(GUID, FROM, firstEnd, 0, 500, "ASC"))
        .thenReturn(List.of(raw("first", "first", at("10:15"))));
    when(bb.getMessagesInChat(GUID, secondStart, TO, 0, 500, "ASC"))
        .thenReturn(List.of(raw("second", "second", at("13:45"))));

    RetrievalResult result =
        retriever.retrieveChronological(
            request(
                new MembershipInterval(FROM, firstEnd), new MembershipInterval(secondStart, TO)));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("first", "second");
    assertThat(result.pageCount()).isEqualTo(2);
    verify(bb, never()).getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC");
  }

  @Test
  void neighborQueriesStayInsideTheHitsAuthorizedInterval() {
    Instant membershipStart = at("11:00");
    Instant membershipEnd = at("13:00");
    when(bb.searchConversationHistory(GUID, "Wordle", membershipStart, membershipEnd, 500, 0))
        .thenReturn(List.of(searchMessage(HIT_GUID, "Wordle", at("12:00"))));
    when(bb.getMessagesInChat(GUID, membershipStart, at("12:00"), 0, 4, "DESC"))
        .thenReturn(List.of());
    when(bb.getMessagesInChat(GUID, at("12:00"), membershipEnd, 0, 4, "ASC")).thenReturn(List.of());

    retriever.retrieveExact(
        request(new MembershipInterval(membershipStart, membershipEnd)), plan("Wordle"));

    verify(bb).getMessagesInChat(GUID, membershipStart, at("12:00"), 0, 4, "DESC");
    verify(bb).getMessagesInChat(GUID, at("12:00"), membershipEnd, 0, 4, "ASC");
    verify(bb, never()).getMessagesInChat(GUID, FROM, at("12:00"), 0, 4, "DESC");
    verify(bb, never()).getMessagesInChat(GUID, at("12:00"), TO, 0, 4, "ASC");
  }

  @Test
  void passesTheOperationRemainingDurationToEveryQaSourceCall() {
    when(bb.searchConversationHistory(GUID, "Wordle", FROM, TO, 500, 0)).thenReturn(List.of());

    retriever.retrieveExact(request(activeFrom("10:00")), plan("Wordle"));

    verify(bb)
        .searchConversationHistoryForQuestion(
            GUID, "Wordle", FROM, TO, 500, 0, Duration.ofMinutes(30));
  }

  @Test
  void journalFallbackCoverageEndsAtTheLastAvailableJournalMessage() {
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenReturn(Collections.nCopies(500, raw("bluebubbles", "newer", at("12:30"))));
    when(bb.getMessagesInChat(GUID, FROM, TO, 500, 500, "ASC"))
        .thenThrow(new IllegalStateException("unavailable"));
    when(store.findMessages(CONVERSATION_ID, FROM, TO.minusNanos(1)))
        .thenReturn(List.of(journal("journal", "account-2", "available", at("12:00"))));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal", "bluebubbles");
    assertThat(result.coverageThrough()).isEqualTo(at("12:00"));
    assertThat(result.partialReason()).isEqualTo("source_unavailable");
  }

  @Test
  void doesNotUseJournalWhenChronologicalPagingHitsItsBudget() {
    retriever = retriever(5, 500, 1, 3, 300_000, NOW);
    when(bb.getMessagesInChat(GUID, FROM, TO, 0, 500, "ASC"))
        .thenReturn(Collections.nCopies(500, raw("duplicate", "first", at("11:30"))));

    RetrievalResult result = retriever.retrieveChronological(request(activeFrom("10:00")));

    assertThat(result.partialReason()).isEqualTo("history_limit");
    verifyNoInteractions(store);
  }

  private ConversationQuestionHistoryRetriever retriever(
      int maxTerms,
      int pageSize,
      int maxPages,
      int neighbors,
      int maxCharacters,
      Instant clockInstant) {
    return retriever(
        maxTerms,
        pageSize,
        maxPages,
        neighbors,
        maxCharacters,
        Clock.fixed(clockInstant, ZoneOffset.UTC));
  }

  private ConversationQuestionHistoryRetriever retriever(
      int maxTerms, int pageSize, int maxPages, int neighbors, int maxCharacters, Clock clock) {
    return new ConversationQuestionHistoryRetriever(
        bb, store, mapper, maxTerms, pageSize, maxPages, neighbors, maxCharacters, clock);
  }

  private RetrievalRequest request(MembershipInterval... memberships) {
    return request("bluebubbles", memberships);
  }

  private RetrievalRequest request(String transport, MembershipInterval... memberships) {
    return new RetrievalRequest(
        ACCOUNT_ID,
        new ConversationRecord(
            CONVERSATION_ID, transport, GUID, true, "Group", FROM, ACCOUNT_ID, TO),
        List.of(memberships),
        FROM,
        TO,
        DEADLINE);
  }

  private MembershipInterval activeFrom(String time) {
    return new MembershipInterval(at(time), null);
  }

  private SearchPlan plan(String... terms) {
    return new SearchPlan(List.of(terms), null, null, null);
  }

  private List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> historyPage(int size) {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      messages.add(raw("neighbor-" + index, "x", FROM.plusSeconds(index + 1L)));
    }
    return messages;
  }

  private Message searchMessage(UUID guid, String text, Instant timestamp) {
    return searchMessage(guid, text, timestamp, null);
  }

  private Message searchMessage(UUID guid, String text, Instant timestamp, String sender) {
    return new Message()
        .guid(guid)
        .text(text)
        .dateCreated(timestamp.toEpochMilli())
        .isFromMe(false)
        .isSystemMessage(false)
        .isServiceMessage(false)
        .handle(sender == null ? null : new MessageHandle().address(sender))
        .chats(List.of(new Chat().guid(GUID)));
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner raw(
      String guid, String text, Instant timestamp) {
    return raw(guid, text, timestamp, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner raw(
      String guid, String text, Instant timestamp, String sender) {
    return new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
        .guid(guid)
        .text(text)
        .isFromMe(false)
        .isSystemMessage(false)
        .isServiceMessage(false)
        .handle(new ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle().address(sender))
        .chats(List.of(new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner().guid(GUID)))
        .dateCreated(timestamp.toEpochMilli());
  }

  private JournalMessage journal(
      String guid, String senderAccountId, String text, Instant timestamp) {
    return new JournalMessage(
        guid, CONVERSATION_ID, senderAccountId, text, timestamp, false, false, "hash-" + guid);
  }

  private Instant at(String time) {
    return Instant.parse("2026-08-09T" + time + ":00Z");
  }

  private static final class TestAccounts {
    private TestAccounts() {}

    private static AgentAccountResolver.ResolvedAccount resolved(String accountId, String name) {
      Instant now = Instant.parse("2026-08-09T12:00:00Z");
      io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity account =
          new io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity(
              accountId, now, now);
      account.setGlobalContactName(name);
      return new AgentAccountResolver.ResolvedAccount(account, List.of());
    }
  }

  private static final class AdvancingClock extends Clock {
    private final List<Instant> instants;
    private final java.time.ZoneId zone;
    private int index;

    private AdvancingClock(List<Instant> instants, java.time.ZoneId zone) {
      this.instants = List.copyOf(instants);
      this.zone = zone;
    }

    @Override
    public java.time.ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(java.time.ZoneId requestedZone) {
      return new AdvancingClock(instants, requestedZone);
    }

    @Override
    public Instant instant() {
      Instant value = instants.get(Math.min(index, instants.size() - 1));
      index++;
      return value;
    }
  }
}
