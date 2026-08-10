package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistorySource;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindow;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindowCursor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.MembershipInterval;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

  private final BBHttpClientWrapper bb = Mockito.mock(BBHttpClientWrapper.class);
  private final ConversationMemoryStore store = Mockito.mock(ConversationMemoryStore.class);
  private final AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
  private final ConversationHistoryMessageMapper mapper =
      Mockito.spy(
          new ConversationHistoryMessageMapper(
              new ConversationParticipantResolver(
                  accountResolver, bb, Clock.fixed(NOW, ZoneOffset.UTC))));
  private ConversationQuestionHistoryRetriever retriever;

  @BeforeEach
  void setUp() {
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
    retriever = retriever(100, NOW);
  }

  @Test
  void retrievesNewestFiveHundredEligibleMessagesAndReturnsThemChronologically() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> descending = new ArrayList<>(500);
    for (int index = 499; index >= 0; index--) {
      descending.add(raw("recent-" + index, "message " + index, FROM.plusSeconds(index + 1L)));
    }
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 500, "DESC", Duration.ofMinutes(30)))
        .thenReturn(descending);

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 500);

    assertThat(window.messages())
        .hasSize(500)
        .isSortedAccordingTo(Comparator.comparing(QuestionMessage::timestamp));
    assertThat(window.messages().getFirst().messageGuid()).isEqualTo("recent-0");
    assertThat(window.messages().getLast().messageGuid()).isEqualTo("recent-499");
    assertThat(window.nextCursor()).isNotNull();
    assertThat(window.sourceExhausted()).isFalse();
  }

  @Test
  void journalCursorRetrievesImmediatelyOlderWindowWithoutOverlap() {
    JournalMessage newest = journal("journal-newest", "account-2", "newest", at("12:02"));
    JournalMessage middle = journal("journal-middle", "account-2", "middle", at("12:01"));
    JournalMessage oldest = journal("journal-oldest", "account-2", "oldest", at("12:00"));
    when(store.findMessagePageDescending(
            CONVERSATION_ID, FROM, TO, null, null, 2, Duration.ofMinutes(30)))
        .thenReturn(List.of(newest, middle));
    when(store.findMessagePageDescending(
            CONVERSATION_ID,
            FROM,
            TO,
            middle.sourceTimestamp(),
            middle.messageGuid(),
            2,
            Duration.ofMinutes(30)))
        .thenReturn(List.of(oldest));

    RetrievalRequest request = request("lxmf", activeFrom("10:00"));
    HistoryWindow first = retriever.retrieveWindow(request, null, 2);
    HistoryWindow second = retriever.retrieveWindow(request, first.nextCursor(), 2);

    assertThat(first.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal-middle", "journal-newest");
    assertThat(second.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal-oldest")
        .doesNotContainAnyElementsOf(
            first.messages().stream().map(QuestionMessage::messageGuid).toList());
    assertThat(second.sourceExhausted()).isTrue();
    assertThat(second.nextCursor()).isNull();
    verifyNoInteractions(bb);
  }

  @Test
  void blueBubblesCursorRetrievesImmediatelyOlderWindowWithoutOverlap() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner newest =
        raw("bb-newest", "newest", at("12:02"));
    ApiV1ChatChatGuidMessageGet200ResponseDataInner middle =
        raw("bb-middle", "middle", at("12:01"));
    ApiV1ChatChatGuidMessageGet200ResponseDataInner oldest =
        raw("bb-oldest", "oldest", at("12:00"));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(newest, middle));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 2, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(oldest));

    RetrievalRequest request = request(activeFrom("10:00"));
    HistoryWindow first = retriever.retrieveWindow(request, null, 2);
    HistoryWindow second = retriever.retrieveWindow(request, first.nextCursor(), 2);

    assertThat(first.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("bb-middle", "bb-newest");
    assertThat(second.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("bb-oldest")
        .doesNotContainAnyElementsOf(
            first.messages().stream().map(QuestionMessage::messageGuid).toList());
    assertThat(second.sourceExhausted()).isTrue();
  }

  @Test
  void filtersEveryWindowByMembershipWithoutChargingIneligibleRowsToTheEligibleLimit() {
    MembershipInterval membership = new MembershipInterval(at("11:00"), at("13:00"));
    when(bb.getMessagesInChatForQuestion(
            GUID, at("11:00"), at("13:00"), 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(
            List.of(
                raw("outside", "not authorized", at("13:30")),
                raw("eligible-newer", "newer", at("12:30"))));
    when(bb.getMessagesInChatForQuestion(
            GUID, at("11:00"), at("13:00"), 2, 1, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(raw("eligible-older", "older", at("11:30"))));

    HistoryWindow window = retriever.retrieveWindow(request(membership), null, 2);

    assertThat(window.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("eligible-older", "eligible-newer");
    assertThat(window.pageCount()).isEqualTo(2);
  }

  @Test
  void searchesDisjointMembershipIntervalsFromNewestToOldest() {
    MembershipInterval older = new MembershipInterval(at("10:00"), at("10:30"));
    MembershipInterval newer = new MembershipInterval(at("12:00"), at("12:30"));
    when(bb.getMessagesInChatForQuestion(
            GUID, at("12:00"), at("12:30"), 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(raw("newer", "newer", at("12:15"))));
    when(bb.getMessagesInChatForQuestion(
            GUID, at("10:00"), at("10:30"), 0, 1, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(raw("older", "older", at("10:15"))));

    HistoryWindow window = retriever.retrieveWindow(request(older, newer), null, 2);

    assertThat(window.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("older", "newer");
  }

  @Test
  void blueBubblesFailureContinuesFromTheSamePositionThroughJournalHistory() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner newest =
        raw("bb-newest", "newest", at("12:02"));
    ApiV1ChatChatGuidMessageGet200ResponseDataInner middle =
        raw("bb-middle", "middle", at("12:01"));
    JournalMessage oldest = journal("journal-oldest", "account-2", "oldest", at("12:00"));
    JournalMessage older = journal("journal-older", "account-2", "older", at("11:59"));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(newest, middle));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 2, 1, "DESC", Duration.ofMinutes(30)))
        .thenThrow(new IllegalStateException("BlueBubbles unavailable"));
    when(store.findMessagePageDescending(
            CONVERSATION_ID, FROM, TO, at("12:01"), "bb-middle", 1, Duration.ofMinutes(30)))
        .thenReturn(List.of(oldest));
    when(store.findMessagePageDescending(
            CONVERSATION_ID,
            FROM,
            TO,
            oldest.sourceTimestamp(),
            oldest.messageGuid(),
            1,
            Duration.ofMinutes(30)))
        .thenReturn(List.of(older));

    RetrievalRequest request = request(activeFrom("10:00"));
    HistoryWindow first = retriever.retrieveWindow(request, null, 2);
    HistoryWindow fallback = retriever.retrieveWindow(request, first.nextCursor(), 1);
    HistoryWindow continued = retriever.retrieveWindow(request, fallback.nextCursor(), 1);

    assertThat(fallback.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal-oldest");
    assertThat(fallback.nextCursor().source()).isEqualTo(HistorySource.JOURNAL);
    assertThat(fallback.windowComplete()).isFalse();
    assertThat(fallback.partialReason()).isEqualTo("source_unavailable");
    assertThat(continued.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("journal-older")
        .doesNotContainAnyElementsOf(
            first.messages().stream().map(QuestionMessage::messageGuid).toList());
    assertThat(continued.windowComplete()).isFalse();
    assertThat(continued.partialReason()).isEqualTo("source_unavailable");
  }

  @Test
  void windowOrderingUsesMessageGuidAsTheEqualTimestampTieBreaker() {
    Instant timestamp = at("12:00");
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(
            List.of(raw("message-b", "second", timestamp), raw("message-a", "first", timestamp)));

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 2);

    assertThat(window.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("message-a", "message-b");
  }

  @Test
  void duplicateRawGuidsDoNotConsumeTheEligibleWindowLimit() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner duplicate =
        raw("duplicate", "same", at("12:01"));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(duplicate, duplicate));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 2, 1, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(raw("older", "older", at("12:00"))));

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 2);

    assertThat(window.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("older", "duplicate");
    verify(mapper, times(1))
        .fromBlueBubbles(
            eq(duplicate),
            eq(ACCOUNT_ID),
            any(ConversationHistoryMessageMapper.MappingSession.class));
  }

  @Test
  void upgradedIdentityForADuplicateGuidReplacesRatherThanDuplicatesTheWindowMessage() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner withoutIdentity =
        raw("duplicate", "same", at("12:01")).handle(null);
    ApiV1ChatChatGuidMessageGet200ResponseDataInner withIdentity =
        raw("duplicate", "same", at("12:01"));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(withoutIdentity, withIdentity));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 2, 1, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(raw("older", "older", at("12:00"))));

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 2);

    assertThat(window.messages())
        .extracting(QuestionMessage::messageGuid)
        .containsExactly("older", "duplicate");
    assertThat(window.messages().getLast().participant()).isEqualTo("participant ending 0199");
  }

  @Test
  void windowStopsAtTheDeadlineWithoutCallingAHistorySource() {
    retriever = retriever(100, DEADLINE.plusSeconds(1));

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 500);

    assertThat(window.messages()).isEmpty();
    assertThat(window.windowComplete()).isFalse();
    assertThat(window.partialReason()).isEqualTo("time_limit");
    assertThat(window.pageCount()).isZero();
    assertThat(window.nextCursor()).isNotNull();
    verifyNoInteractions(bb, store);
  }

  @Test
  void windowStopsAtTheConfiguredSourcePageLimitWithAContinuationCursor() {
    retriever = retriever(1, NOW);
    ApiV1ChatChatGuidMessageGet200ResponseDataInner duplicate =
        raw("duplicate", "same", at("12:01"));
    when(bb.getMessagesInChatForQuestion(GUID, FROM, TO, 0, 2, "DESC", Duration.ofMinutes(30)))
        .thenReturn(List.of(duplicate, duplicate));

    HistoryWindow window = retriever.retrieveWindow(request(activeFrom("10:00")), null, 2);

    assertThat(window.messages()).singleElement();
    assertThat(window.windowComplete()).isFalse();
    assertThat(window.partialReason()).isEqualTo("history_limit");
    assertThat(window.pageCount()).isEqualTo(1);
    assertThat(window.nextCursor().rawOffset()).isEqualTo(2);
  }

  @Test
  void rejectsInvalidWindowLimitsAndCursorsWithoutSourceAccess() {
    RetrievalRequest blueBubblesRequest = request(activeFrom("10:00"));
    RetrievalRequest journalRequest = request("lxmf", activeFrom("10:00"));

    assertThatThrownBy(() -> retriever.retrieveWindow(blueBubblesRequest, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> retriever.retrieveWindow(blueBubblesRequest, null, 501))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                retriever.retrieveWindow(
                    journalRequest,
                    new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 0, 0, null, null),
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                retriever.retrieveWindow(
                    blueBubblesRequest,
                    new HistoryWindowCursor(HistorySource.BLUEBUBBLES, 1, 0, null, null),
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(bb, store);
  }

  private ConversationQuestionHistoryRetriever retriever(int maxPages, Instant clockInstant) {
    return new ConversationQuestionHistoryRetriever(
        bb, store, mapper, maxPages, Clock.fixed(clockInstant, ZoneOffset.UTC));
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
}
