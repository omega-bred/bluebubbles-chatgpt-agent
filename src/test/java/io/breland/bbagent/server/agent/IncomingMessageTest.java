package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    assertTrue(message.isGroup());
  }

  @Test
  void likelyGroupChatDoesNotInferFromChatGuidPrefixWhenGroupFlagIsFalse() {
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

    assertFalse(message.isGroup());
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

    assertFalse(message.isGroup());
  }

  @Test
  void metricTransportMapsBlueBubblesToImessageAndPreservesLxmf() {
    IncomingMessage imessage =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
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
    IncomingMessage lxmf =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_LXMF,
            "lxmf:abc",
            "msg-2",
            null,
            "hello",
            false,
            "LXMF",
            "abc",
            false,
            Instant.now(),
            List.of(),
            false);

    assertEquals(IncomingMessage.METRIC_TRANSPORT_IMESSAGE, imessage.metricTransport());
    assertEquals(IncomingMessage.TRANSPORT_LXMF, lxmf.metricTransport());
  }

  @Test
  void logSummaryExcludesMessageTextAndSender() {
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "chat-guid",
            "msg-1",
            "thread-root",
            "private message body",
            false,
            "iMessage",
            "sender@example.com",
            false,
            Instant.parse("2026-05-30T00:00:00Z"),
            List.of(),
            false);

    String summary = message.logSummary();

    assertTrue(summary.contains("transport=bluebubbles"));
    assertTrue(summary.contains("chatGuid=chat-guid"));
    assertTrue(summary.contains("messageGuid=msg-1"));
    assertTrue(summary.contains("hasText=true"));
    assertFalse(summary.contains("private message body"));
    assertFalse(summary.contains("sender@example.com"));
  }

  @Test
  void logSummaryHandlesNullMessage() {
    assertEquals("message=null", IncomingMessage.logSummary(null));
  }
}
