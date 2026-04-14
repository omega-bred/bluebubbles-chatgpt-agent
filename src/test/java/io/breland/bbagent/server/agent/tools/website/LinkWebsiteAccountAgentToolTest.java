package io.breland.bbagent.server.agent.tools.website;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LinkWebsiteAccountAgentToolTest {

  @Test
  void createsUserFacingAccountLink() {
    WebsiteAccountService service = Mockito.mock(WebsiteAccountService.class);
    when(service.createLinkToken(Mockito.any()))
        .thenReturn(
            new WebsiteAccountService.CreatedLinkToken(
                "https://chatagent.example/account/link?token=abc",
                Instant.parse("2026-04-14T00:00:00Z"),
                "Alice"));
    LinkWebsiteAccountAgentTool tool = new LinkWebsiteAccountAgentTool(service);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(new ObjectMapper());
    ToolContext context = new ToolContext(agent, message(), null);

    String output = tool.getTool().handler().apply(context, new ObjectMapper().createObjectNode());

    assertTrue(output.contains("https://chatagent.example/account/link?token=abc"));
    assertTrue(output.contains("user_facing_text"));
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "link my account",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
