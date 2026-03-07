package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomingMessageTest {

  @Test
  void likelyGroupChatUsesGroupFlag() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;user-1",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            true,
            Instant.now(),
            List.of(),
            false);

    assertTrue(message.isLikelyGroupChat());
  }

  @Test
  void likelyGroupChatUsesChatGuidPrefixWhenGroupFlagIsFalse() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;chat123",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    assertTrue(message.isLikelyGroupChat());
  }

  @Test
  void likelyGroupChatReturnsFalseForDirectChatsWithoutGroupFlag() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;user-1",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    assertFalse(message.isLikelyGroupChat());
  }
}
