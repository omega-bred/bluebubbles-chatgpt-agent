package io.breland.bbagent.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ToolContextTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void exposesCleanCurrentMessageFields() {
    ToolContext context =
        new ToolContext(
            agent(mapper),
            new IncomingMessage(
                "chat-1",
                "msg-1",
                "thread-1",
                "hello",
                true,
                BBMessageAgent.IMESSAGE_SERVICE,
                "+18035551212",
                true,
                Instant.parse("2026-05-05T00:00:00Z"),
                List.of(),
                false),
            null);

    assertEquals("chat-1", context.chatGuid());
    assertEquals("msg-1", context.messageGuid());
    assertEquals("thread-1", context.threadOriginatorGuid());
    assertEquals("+18035551212", context.sender());
    assertTrue(context.isGroupChat());
  }

  @Test
  void returnsNullForBlankCurrentMessageFields() {
    ToolContext context =
        new ToolContext(
            agent(mapper),
            new IncomingMessage(
                " ",
                "",
                "\t",
                "hello",
                false,
                BBMessageAgent.IMESSAGE_SERVICE,
                " ",
                false,
                Instant.parse("2026-05-05T00:00:00Z"),
                List.of(),
                false),
            null);

    assertNull(context.chatGuid());
    assertNull(context.messageGuid());
    assertNull(context.threadOriginatorGuid());
    assertNull(context.sender());
    assertFalse(context.isGroupChat());
  }

  @Test
  void stringifiesWithFallbackWhenMapperIsMissing() {
    ToolContext context = new ToolContext(agent(null), null, null);

    assertEquals("fallback", context.stringify(Map.of("ok", true), "fallback"));
  }

  private BBMessageAgent agent(ObjectMapper mapper) {
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    return agent;
  }
}
