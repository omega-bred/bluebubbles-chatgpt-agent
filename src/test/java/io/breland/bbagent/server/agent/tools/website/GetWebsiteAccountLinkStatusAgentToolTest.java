package io.breland.bbagent.server.agent.tools.website;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GetWebsiteAccountLinkStatusAgentToolTest {

  @Test
  void checksCurrentSenderLinkStatus() {
    WebsiteAccountService service = Mockito.mock(WebsiteAccountService.class);
    when(service.getLinkStatus("Alice", "iMessage;+;chat-1"))
        .thenReturn(
            new WebsiteAccountService.SenderLinkStatus(
                "Alice",
                "Alice",
                "iMessage;+;chat-1|Alice",
                true,
                true,
                1,
                1,
                new WebsiteModelAccessSummary()
                    .accountBase("Alice")
                    .plan(WebsiteModelAccessSummary.PlanEnum.PREMIUM)
                    .isPremium(true)
                    .currentModel("chatgpt")
                    .currentModelLabel("ChatGPT")
                    .modelSelectionAllowed(true)
                    .modelSelectionConfigurable(false)
                    .availableModels(List.of()),
                Instant.parse("2026-04-14T00:00:00Z")));
    GetWebsiteAccountLinkStatusAgentTool tool = new GetWebsiteAccountLinkStatusAgentTool(service);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(new ObjectMapper());
    ToolContext context = new ToolContext(agent, message(), null);

    String output = tool.getTool().handler().apply(context, new ObjectMapper().createObjectNode());

    assertTrue(output.contains("\"linked\":true"));
    assertTrue(output.contains("\"exact_chat_linked\":true"));
    assertTrue(output.contains("\"model_access\""));
    assertTrue(output.contains("\"current_model\":\"chatgpt\""));
    assertTrue(output.contains("This iMessage sender is linked"));
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "am I linked?",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
