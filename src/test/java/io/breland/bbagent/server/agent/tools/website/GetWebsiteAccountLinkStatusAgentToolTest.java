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
    IncomingMessage message = message();
    when(service.getLinkStatus(message)).thenReturn(status("account-1"));
    GetWebsiteAccountLinkStatusAgentTool tool = new GetWebsiteAccountLinkStatusAgentTool(service);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(new ObjectMapper());
    ToolContext context = new ToolContext(agent, message, null);

    String output = tool.getTool().handler().apply(context, new ObjectMapper().createObjectNode());

    assertTrue(output.contains("\"linked\":true"));
    assertTrue(output.contains("\"exact_chat_linked\":true"));
    assertTrue(output.contains("\"account_id\":\"account-1\""));
    assertTrue(output.contains("\"model_access\""));
    assertTrue(output.contains("\"current_model\":\"chatgpt\""));
    assertTrue(output.contains("This chat identity is linked"));
    Mockito.verify(service).getLinkStatus(message);
  }

  @Test
  void checksLxmfStatusUsingCurrentMessageTransport() {
    WebsiteAccountService service = Mockito.mock(WebsiteAccountService.class);
    IncomingMessage message = lxmfMessage();
    when(service.getLinkStatus(message)).thenReturn(status("account-lxmf"));
    GetWebsiteAccountLinkStatusAgentTool tool = new GetWebsiteAccountLinkStatusAgentTool(service);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(new ObjectMapper());
    ToolContext context = new ToolContext(agent, message, null);

    String output = tool.getTool().handler().apply(context, new ObjectMapper().createObjectNode());

    assertTrue(output.contains("\"account_id\":\"account-lxmf\""));
    assertTrue(output.contains("This chat identity is linked"));
    Mockito.verify(service).getLinkStatus(message);
  }

  @Test
  void explicitLookupDefaultsToCurrentTransport() {
    WebsiteAccountService service = Mockito.mock(WebsiteAccountService.class);
    when(service.getLinkStatus("lxmf", "ccdd", "lxmf:aabb"))
        .thenReturn(status("account-lxmf-ccdd"));
    GetWebsiteAccountLinkStatusAgentTool tool = new GetWebsiteAccountLinkStatusAgentTool(service);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    ObjectMapper mapper = new ObjectMapper();
    when(agent.getObjectMapper()).thenReturn(mapper);
    ToolContext context = new ToolContext(agent, lxmfMessage(), null);
    var args = mapper.createObjectNode().put("sender", "ccdd");

    String output = tool.getTool().handler().apply(context, args);

    assertTrue(output.contains("\"account_id\":\"account-lxmf-ccdd\""));
    Mockito.verify(service).getLinkStatus("lxmf", "ccdd", "lxmf:aabb");
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

  private IncomingMessage lxmfMessage() {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_LXMF,
        "lxmf:aabb",
        "msg-1",
        null,
        "am I linked?",
        false,
        "LXMF",
        "aabb",
        false,
        Instant.now(),
        List.of(),
        false);
  }

  private WebsiteAccountService.SenderLinkStatus status(String accountId) {
    return new WebsiteAccountService.SenderLinkStatus(
        accountId,
        true,
        true,
        1,
        1,
        new WebsiteModelAccessSummary()
            .accountId(accountId)
            .plan(WebsiteModelAccessSummary.PlanEnum.PREMIUM)
            .isPremium(true)
            .currentModel("chatgpt")
            .currentModelLabel("ChatGPT")
            .modelSelectionAllowed(true)
            .modelSelectionConfigurable(false)
            .availableModels(List.of()),
        Instant.parse("2026-04-14T00:00:00Z"));
  }
}
