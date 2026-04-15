package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAccountIdentityTest {

  @Test
  void prefersSenderForAccountBaseAndUsesChatSenderForCalendar() {
    IncomingMessage message =
        new IncomingMessage(
            "chat-guid",
            "message-guid",
            null,
            "hello",
            false,
            "iMessage",
            "+15551234567",
            true,
            Instant.now(),
            List.of(),
            false);

    AgentAccountIdentity identity = AgentAccountIdentity.from(message);

    assertTrue(identity.hasAccountBase());
    assertEquals("+15551234567", identity.accountBase());
    assertEquals("+15551234567", identity.coderAccountBase());
    assertEquals("chat-guid|+15551234567", identity.gcalAccountBase());
  }

  @Test
  void fallsBackToChatGuidWhenSenderIsMissing() {
    AgentAccountIdentity identity = AgentAccountIdentity.from(null, "chat-guid");

    assertTrue(identity.hasAccountBase());
    assertEquals("chat-guid", identity.accountBase());
    assertEquals("chat-guid", identity.coderAccountBase());
    assertEquals("chat-guid", identity.gcalAccountBase());
  }

  @Test
  void emptyIdentityHasNoAccountBase() {
    assertFalse(AgentAccountIdentity.from(null, " ").hasAccountBase());
  }
}
