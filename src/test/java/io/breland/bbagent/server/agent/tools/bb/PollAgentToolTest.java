package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PollAgentToolTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sendPollUsesCurrentConversationAndReturnsPollJson() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", null, null);
    ToolContext context = toolContext(message, null);
    JsonNode args =
        mapper.readTree(
            """
            {
              "title": "Lunch?",
              "options": [
                { "text": "Sushi" },
                { "text": "Pizza" }
              ]
            }
            """);

    String output = new SendPollAgentTool(wrapper).getTool().handler().apply(context, args);

    assertEquals("iMessage;-;+15555550123", wrapper.lastPollChatGuid);
    assertEquals("Lunch?", wrapper.lastPollTitle);
    assertEquals(List.of("Sushi", "Pizza"), wrapper.lastPollOptionTexts);
    assertTrue(output.contains("\"title\":\"Lunch?\""));
  }

  @Test
  void readPollDefaultsToAssociatedPollGuid() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", "poll-guid", null);
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertEquals("poll-guid", wrapper.lastReadPollGuid);
    assertTrue(output.contains("\"messageGuid\":\"poll-guid\""));
  }

  private ToolContext toolContext(IncomingMessage message, AgentWorkflowContext workflowContext) {
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    when(agent.consumeMessageResponseQuota(eq(message), isNull())).thenReturn(true);
    return new ToolContext(agent, message, workflowContext);
  }

  private static IncomingMessage incomingMessage(
      String chatGuid, String associatedMessageGuid, String replyToGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        "message-guid",
        null,
        "hello",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        false,
        Instant.now(),
        List.of(),
        "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls",
        associatedMessageGuid,
        replyToGuid,
        false);
  }

  private static final class CapturingBBHttpClientWrapper extends BBHttpClientWrapper {
    private final ObjectMapper mapper;
    private String lastPollChatGuid;
    private String lastPollTitle;
    private List<String> lastPollOptionTexts;
    private String lastReadPollGuid;

    CapturingBBHttpClientWrapper(ObjectMapper mapper) {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
      this.mapper = mapper;
    }

    @Override
    public JsonNode sendPollJson(String chatGuid, String title, List<PollSendOption> options) {
      this.lastPollChatGuid = chatGuid;
      this.lastPollTitle = title;
      this.lastPollOptionTexts = options.stream().map(PollSendOption::text).toList();
      return mapper
          .createObjectNode()
          .putObject("poll")
          .put("messageGuid", "poll-guid")
          .put("title", title);
    }

    @Override
    public JsonNode readPollJson(String messageGuid) {
      this.lastReadPollGuid = messageGuid;
      return mapper.createObjectNode().put("messageGuid", messageGuid).put("title", "Lunch?");
    }
  }
}
