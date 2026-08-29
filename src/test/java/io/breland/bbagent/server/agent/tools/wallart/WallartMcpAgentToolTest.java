package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WallartMcpAgentToolTest {

  @Test
  void submitsTrimmedPromptThroughMcpClient() {
    WallartMcpClient client = mock(WallartMcpClient.class);
    WallartConversationAccess access = mock(WallartConversationAccess.class);
    IncomingMessage message = message();
    when(access.isAllowed(message)).thenReturn(true);
    when(client.showNewArt("a bright abstract landscape")).thenReturn("{\"status\":\"submitted\"}");
    AgentTool tool = new WallartMcpAgentTool(client, access).getTool();
    ObjectNode args = new ObjectMapper().createObjectNode();
    args.put("prompt", "  a bright abstract landscape  ");
    ToolContext context = ToolContextFixture.with(message).build();

    assertEquals("{\"status\":\"submitted\"}", tool.handler().apply(context, args));
    verify(client).showNewArt("a bright abstract landscape");
  }

  @Test
  void refusesInvocationWhenConversationIsNotAllowed() {
    WallartMcpClient client = mock(WallartMcpClient.class);
    WallartConversationAccess access = mock(WallartConversationAccess.class);
    IncomingMessage message = message();
    when(access.isAllowed(message)).thenReturn(false);
    AgentTool tool = new WallartMcpAgentTool(client, access).getTool();
    ObjectNode args = new ObjectMapper().createObjectNode();
    args.put("prompt", "anything");

    assertEquals(
        "The wallart tool is not available in this conversation.",
        tool.handler().apply(ToolContextFixture.with(message).build(), args));
    verify(client, never()).showNewArt("anything");
  }

  private static IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;-;+18033861737",
        "message-guid",
        null,
        "show something new",
        false,
        "iMessage",
        "+18033861737",
        false,
        Instant.EPOCH,
        List.of(),
        false);
  }
}
