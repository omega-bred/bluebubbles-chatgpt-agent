package io.breland.bbagent.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationStateTest {
  private static final int IDENTIFIER_CAPACITY = BBMessageAgent.MAX_HISTORY * 4;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void seenIdentifiersEvictInInsertionOrderWithoutRefreshingDuplicates(boolean hasGuid) {
    ConversationState state = new ConversationState();
    IncomingMessage oldest = message(0, hasGuid);
    for (int i = 0; i < IDENTIFIER_CAPACITY; i++) {
      state.markIncomingMessageSeen(message(i, hasGuid));
    }

    state.markIncomingMessageSeen(oldest);
    assertThat(state.hasSeenIncomingMessage(oldest)).isTrue();
    IncomingMessage newest = message(IDENTIFIER_CAPACITY, hasGuid);
    state.markIncomingMessageSeen(newest);

    assertThat(state.hasSeenIncomingMessage(oldest)).isFalse();
    assertThat(state.hasSeenIncomingMessage(message(1, hasGuid))).isTrue();
    assertThat(state.hasSeenIncomingMessage(newest)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void recordedIdentifiersSuppressDuplicatesUntilEvicted(boolean hasGuid) {
    ConversationState state = new ConversationState();
    IncomingMessage oldest = message(0, hasGuid);
    for (int i = 0; i < IDENTIFIER_CAPACITY; i++) {
      state.recordIncomingTurnIfAbsent(message(i, hasGuid));
    }

    List<ConversationTurn> history = state.history();
    state.recordIncomingTurnIfAbsent(oldest);
    assertThat(state.history()).isEqualTo(history);

    state.recordIncomingTurnIfAbsent(message(IDENTIFIER_CAPACITY, hasGuid));
    state.recordIncomingTurnIfAbsent(oldest);
    assertThat(state.history()).hasSize(BBMessageAgent.MAX_HISTORY);
    assertThat(state.history().getLast().content()).isEqualTo(oldest.summaryForHistory());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void flushedPendingTurnsShareTheRecordedIdentifierWindow(boolean hasGuid) {
    ConversationState state = new ConversationState();
    IncomingMessage oldest = message(0, hasGuid);
    for (int i = 0; i < IDENTIFIER_CAPACITY; i++) {
      recordPending(state, message(i, hasGuid));
    }

    state.recordPendingIncomingTurn(oldest);
    assertThat(state.pendingIncomingTurns()).isEmpty();
    List<ConversationTurn> history = state.history();
    state.recordIncomingTurnIfAbsent(oldest);
    assertThat(state.history()).isEqualTo(history);

    recordPending(state, message(IDENTIFIER_CAPACITY, hasGuid));
    state.recordPendingIncomingTurn(oldest);
    assertThat(state.pendingIncomingTurns()).hasSize(1);
    state.recordPendingIncomingTurnsToHistory();
    assertThat(state.history().getLast().content()).isEqualTo(oldest.summaryForHistory());
  }

  @Test
  void messagesWithoutGuidsDoNotConsumeTheGuidCacheCapacity() {
    ConversationState state = new ConversationState();
    IncomingMessage withGuid = message(0, true);
    state.recordIncomingTurnIfAbsent(withGuid);
    for (int i = 0; i < IDENTIFIER_CAPACITY; i++) {
      state.recordIncomingTurnIfAbsent(message(i, false));
    }

    assertThat(state.hasSeenIncomingMessage(withGuid)).isTrue();
    List<ConversationTurn> history = state.history();
    state.recordIncomingTurnIfAbsent(withGuid);
    assertThat(state.history()).isEqualTo(history);
    state.recordPendingIncomingTurn(withGuid);
    assertThat(state.pendingIncomingTurns()).isEmpty();
  }

  private static void recordPending(ConversationState state, IncomingMessage message) {
    state.recordPendingIncomingTurn(message);
    state.recordPendingIncomingTurnsToHistory();
  }

  private static IncomingMessage message(int index, boolean hasGuid) {
    return new IncomingMessage(
        "chat",
        hasGuid ? "message-" + index : null,
        null,
        "text-" + index,
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "sender",
        false,
        Instant.ofEpochSecond(index),
        List.of(),
        false);
  }
}
