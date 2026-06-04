package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void chatGuidOrNullRequiresNonBlankChatGuid() {
    IncomingMessage message = messageWithChatGuid(" chat-guid ");

    assertEquals(" chat-guid ", IncomingMessage.chatGuidOrNull(message));
    assertNull(IncomingMessage.chatGuidOrNull(null));
    assertNull(IncomingMessage.chatGuidOrNull(messageWithChatGuid(" ")));
  }

  @Test
  void logSummaryOmitsSenderAndText() {
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;+;chat123",
            "msg-1",
            null,
            "secret message",
            false,
            "iMessage",
            "+14025550100",
            false,
            Instant.parse("2026-06-04T15:00:00Z"),
            List.of(),
            false);

    String summary = message.logSummary();

    assertFalse(summary.contains("secret message"));
    assertFalse(summary.contains("+14025550100"));
    assertTrue(summary.contains("senderPresent=true"));
    assertTrue(summary.contains("hasText=true"));
    assertTrue(summary.contains("textLength=14"));
  }

  @Test
  void logFingerprintHashOmitsSenderAndTextFallbackParts() {
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_LXMF,
            "lxmf:abc",
            null,
            null,
            "secret message",
            false,
            "LXMF",
            "sender-secret",
            false,
            Instant.parse("2026-06-04T15:00:00Z"),
            List.of(),
            false);

    String hash = message.logFingerprintHash();

    assertEquals(12, hash.length());
    assertFalse(hash.contains("secret message"));
    assertFalse(hash.contains("sender-secret"));
  }

  private static IncomingMessage messageWithChatGuid(String chatGuid) {
    return new IncomingMessage(
        chatGuid,
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
  }
}
