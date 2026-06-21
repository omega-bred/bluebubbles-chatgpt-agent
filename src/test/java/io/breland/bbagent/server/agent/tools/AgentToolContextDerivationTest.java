package io.breland.bbagent.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventDeleteTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventListTool;
import io.breland.bbagent.server.agent.tools.website.GetWebsiteAccountLinkStatusAgentTool;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentToolContextDerivationTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void modelFacingSchemasDoNotExposeDerivedChatOrSenderSelectors() {
    assertSchemaExcludes(
        new SendPollAgentTool(Mockito.mock(BBHttpClientWrapper.class)).getTool(),
        "conversation_id",
        "chatGuid",
        "chat_guid");
    assertSchemaExcludes(
        new SendReactionAgentTool().getTool(), "chatGuid", "chat_guid", "conversation_id");
    assertSchemaExcludes(
        new GetWebsiteAccountLinkStatusAgentTool(Mockito.mock(WebsiteAccountService.class))
            .getTool(),
        "sender",
        "chat_guid",
        "transport");
    assertSchemaExcludes(
        new GetThreadContextAgentTool(Mockito.mock(BBHttpClientWrapper.class)).getTool(),
        "thread_root_guid");
    assertSchemaExcludes(
        new ScheduledEventListTool(Mockito.mock(CadenceWorkflowLauncher.class)).getTool(),
        "chatGuid",
        "chat_guid");

    String deleteSchema =
        schemaText(
            new ScheduledEventDeleteTool(Mockito.mock(CadenceWorkflowLauncher.class)).getTool());
    assertFalse(deleteSchema.contains("workflowId"));
    assertTrue(deleteSchema.contains("scheduled_event_id"));
  }

  @Test
  void sendReactionDefaultsToCurrentMessageAndChatContext() throws Exception {
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    IncomingMessage message = message("iMessage;+;chat-1", "message-1");
    when(agent.getObjectMapper()).thenReturn(mapper);
    when(agent.canSendResponses(Mockito.<AgentWorkflowContext>isNull())).thenReturn(true);
    when(agent.sendReactionFromTool(
            eq(message),
            eq("iMessage;+;chat-1"),
            eq("message-1"),
            eq("love"),
            Mockito.<Integer>isNull(),
            Mockito.<AgentWorkflowContext>isNull()))
        .thenReturn(true);
    ToolContext context = new ToolContext(agent, message, null);
    JsonNode args = mapper.readTree("{\"reaction\":\"love\"}");

    String output = new SendReactionAgentTool().getTool().handler().apply(context, args);

    assertEquals("sent", output);
    verify(agent)
        .sendReactionFromTool(
            eq(message),
            eq("iMessage;+;chat-1"),
            eq("message-1"),
            eq("love"),
            Mockito.<Integer>isNull(),
            Mockito.<AgentWorkflowContext>isNull());
  }

  @Test
  void scheduledListReturnsSanitizedIdsForCurrentChatOnly() throws Exception {
    CadenceWorkflowLauncher launcher = Mockito.mock(CadenceWorkflowLauncher.class);
    when(launcher.listScheduledWorkflows("scheduled:iMessage;+;chat-1:"))
        .thenReturn(
            List.of(
                new CadenceWorkflowLauncher.ScheduledWorkflowSummary(
                    "scheduled:iMessage;+;chat-1:event-1",
                    "run-1",
                    "CadenceMessageWorkflow",
                    "Running",
                    1L,
                    2L,
                    Map.of("chatGuid", "iMessage;+;chat-1", "task", "check status"))));
    ToolContext context =
        new ToolContext(agentWithMapper(), message("iMessage;+;chat-1", "message-1"), null);

    String output =
        new ScheduledEventListTool(launcher)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    JsonNode response = mapper.readTree(output);
    assertEquals("event-1", response.get(0).get("scheduled_event_id").asText());
    assertFalse(output.contains("iMessage;+;chat-1"));
    assertFalse(output.contains("chatGuid"));
    verify(launcher).listScheduledWorkflows("scheduled:iMessage;+;chat-1:");
  }

  @Test
  void scheduledDeleteDerivesWorkflowIdFromCurrentChat() throws Exception {
    CadenceWorkflowLauncher launcher = Mockito.mock(CadenceWorkflowLauncher.class);
    when(launcher.terminateWorkflow(
            eq("scheduled:iMessage;+;chat-1:event-1"),
            eq("run-1"),
            eq("deleted via scheduled event tool")))
        .thenReturn(true);
    ToolContext context =
        new ToolContext(agentWithMapper(), message("iMessage;+;chat-1", "message-1"), null);
    JsonNode args = mapper.readTree("{\"scheduled_event_id\":\"event-1\",\"run_id\":\"run-1\"}");

    String output = new ScheduledEventDeleteTool(launcher).getTool().handler().apply(context, args);

    assertEquals("deleted", output);
    verify(launcher)
        .terminateWorkflow(
            "scheduled:iMessage;+;chat-1:event-1", "run-1", "deleted via scheduled event tool");
  }

  private static void assertSchemaExcludes(AgentTool tool, String... forbiddenProperties) {
    String schema = schemaText(tool);
    for (String property : forbiddenProperties) {
      assertFalse(schema.contains(property), tool.name() + " should not expose " + property);
    }
  }

  private static String schemaText(AgentTool tool) {
    return String.valueOf(tool.parameters()._additionalProperties());
  }

  private BBMessageAgent agentWithMapper() {
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    return agent;
  }

  private static IncomingMessage message(String chatGuid, String messageGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        messageGuid,
        null,
        "hello",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
