package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationStateTest {

  @Test
  void recordsIncomingTurnOnlyOncePerMessageGuid() {
    ConversationState state = new ConversationState();
    IncomingMessage message = message("msg-1", "hello", Instant.parse("2026-04-28T00:00:00Z"));

    assertTrue(state.recordIncomingTurnIfAbsent(message));
    assertFalse(state.recordIncomingTurnIfAbsent(message));

    assertEquals(1, state.history().size());
    assertTrue(state.hasSeenIncomingMessage(message));
  }

  @Test
  void recordsGuidlessIncomingTurnOnlyOncePerFingerprint() {
    ConversationState state = new ConversationState();
    IncomingMessage message = message(null, "same body", Instant.parse("2026-04-28T00:00:00Z"));
    IncomingMessage duplicate = message(null, "same body", Instant.parse("2026-04-28T00:00:00Z"));

    assertTrue(state.recordIncomingTurnIfAbsent(message));
    assertFalse(state.recordIncomingTurnIfAbsent(duplicate));

    assertEquals(1, state.history().size());
    assertTrue(state.hasSeenIncomingMessage(duplicate));
  }

  @Test
  void evictsOldIncomingIdentifiersAfterBoundIsReached() {
    ConversationState state = new ConversationState();
    IncomingMessage first = message("msg-0", "message 0", Instant.parse("2026-04-28T00:00:00Z"));
    state.markIncomingMessageSeen(first);

    int maxTrackedIdentifiers = BBMessageAgent.MAX_HISTORY * 4;
    for (int i = 1; i <= maxTrackedIdentifiers; i++) {
      state.markIncomingMessageSeen(
          message(
              "msg-" + i, "message " + i, Instant.parse("2026-04-28T00:00:00Z").plusSeconds(i)));
    }

    assertFalse(state.hasSeenIncomingMessage(first));
  }

  private IncomingMessage message(String messageGuid, String text, Instant timestamp) {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        messageGuid,
        null,
        text,
        false,
        "iMessage",
        "Alice",
        false,
        timestamp,
        List.of(),
        false);
  }
}
