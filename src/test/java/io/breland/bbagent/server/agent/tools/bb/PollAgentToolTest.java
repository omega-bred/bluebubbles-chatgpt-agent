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
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
  void sendPollAcceptsStringOptions() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", null, null);
    ToolContext context = toolContext(message, null);
    JsonNode args =
        mapper.readTree(
            """
            {
              "title": "Dinner?",
              "options": [ "Grilled chicken bowls", "Salmon salads" ]
            }
            """);

    String output = new SendPollAgentTool(wrapper).getTool().handler().apply(context, args);

    assertEquals("Dinner?", wrapper.lastPollTitle);
    assertEquals(List.of("Grilled chicken bowls", "Salmon salads"), wrapper.lastPollOptionTexts);
    assertTrue(output.contains("\"title\":\"Dinner?\""));
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

    assertEquals(List.of("poll-guid"), wrapper.readPollGuids);
    assertTrue(output.contains("\"messageGuid\":\"poll-guid\""));
  }

  @Test
  void readPollFormatsOptionLabelsAndVotesForModel() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.pollReadResponse =
        mapper.readTree(
            """
            {
              "messageGuid": "poll-guid",
              "title": "Dinner?",
              "options": [
                { "optionIdentifier": "a", "text": "Sushi" },
                { "optionIdentifier": "b", "text": "Pizza" }
              ],
              "responses": [
                { "handle": "+15555550123", "optionIdentifiers": [ "b" ] }
              ]
            }
            """);
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", "poll-guid", null);
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertTrue(output.contains("Title: Dinner?"));
    assertTrue(output.contains("Sushi - 0 votes"));
    assertTrue(output.contains("Pizza - 1 vote (+15555550123)"));
    assertTrue(output.contains("+15555550123 voted for Pizza"));
    assertTrue(output.contains("\"optionIdentifier\":\"b\""));
  }

  @Test
  void readPollFormatsMultiSelectVotesWithOptionLabels() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.pollReadResponse =
        mapper.readTree(
            """
            {
              "messageGuid": "poll-guid",
              "title": "",
              "optionCount": 3,
              "options": [
                { "optionIdentifier": "choice-1", "text": "O1" },
                { "optionIdentifier": "choice-2", "text": "O2" },
                { "optionIdentifier": "choice-3", "text": "O3" }
              ],
              "responses": [
                { "handle": "+18033861737", "optionIdentifiers": [ "choice-2", "choice-3" ] }
              ]
            }
            """);
    IncomingMessage message = incomingMessage("iMessage;-;+18033861737", "poll-guid", null);
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertTrue(output.contains("Title: (untitled)"));
    assertTrue(output.contains("O1 - 0 votes"));
    assertTrue(output.contains("O2 - 1 vote (+18033861737)"));
    assertTrue(output.contains("O3 - 1 vote (+18033861737)"));
    assertTrue(output.contains("+18033861737 voted for O2, O3"));
  }

  @Test
  void readPollFindsLatestPollInRecentConversationWhenCurrentMessageIsPlainText() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.historyMessages =
        List.of(
            historyMessage("question-guid", null, null),
            historyMessage("vote-guid", pollBundleId(), "p:0/poll-root-guid"),
            historyMessage("older-poll-guid", pollBundleId(), null));
    IncomingMessage message =
        plainIncomingMessage("any;-;+15555550123", "question-guid", "what were the results?");
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertEquals("any;-;+15555550123", wrapper.lastHistoryChatGuid);
    assertEquals("poll-root-guid", wrapper.lastReadPollGuid);
    assertTrue(output.contains("\"messageGuid\":\"poll-root-guid\""));
  }

  @Test
  void readPollFallsBackWhenCurrentReplyTargetIsNotPoll() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.historyMessages =
        List.of(
            historyMessage("question-guid", null, null),
            historyMessage("vote-guid", pollBundleId(), "poll-root-guid"));
    wrapper.failingReadPollGuids.add("plain-reply-guid");
    IncomingMessage message =
        plainIncomingMessage(
            "any;-;+15555550123", "question-guid", "what were the results?", "plain-reply-guid");
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertEquals(List.of("plain-reply-guid", "poll-root-guid"), wrapper.readPollGuids);
    assertEquals("poll-root-guid", wrapper.lastReadPollGuid);
    assertTrue(output.contains("\"messageGuid\":\"poll-root-guid\""));
  }

  @Test
  void readPollFallsBackFromExplicitPollUpdateGuidToAssociatedHistoryPoll() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.historyMessages =
        List.of(
            historyMessage("newer-vote-guid", pollBundleId(), "newer-poll-root-guid"),
            historyMessage("vote-guid", pollBundleId(), "p:0/poll-root-guid"));
    wrapper.failingReadPollGuids.add("vote-guid");
    IncomingMessage message =
        plainIncomingMessage("any;-;+15555550123", "question-guid", "read the poll");
    ToolContext context = toolContext(message, null);
    JsonNode args = mapper.readTree("{\"message_guid\":\"vote-guid\"}");

    String output = new ReadPollAgentTool(wrapper).getTool().handler().apply(context, args);

    assertEquals(List.of("vote-guid", "poll-root-guid"), wrapper.readPollGuids);
    assertEquals("poll-root-guid", wrapper.lastReadPollGuid);
    assertTrue(output.contains("\"messageGuid\":\"poll-root-guid\""));
  }

  @Test
  void readPollNormalizesTapbackStyleMessageGuid() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", "p:0/poll-guid", null);
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertEquals(List.of("poll-guid"), wrapper.readPollGuids);
    assertTrue(output.contains("\"messageGuid\":\"poll-guid\""));
  }

  @Test
  void readPollReturnsToolMessageWhenBlueBubblesFails() throws Exception {
    CapturingBBHttpClientWrapper wrapper = new CapturingBBHttpClientWrapper(mapper);
    wrapper.failReadPoll = true;
    IncomingMessage message = incomingMessage("iMessage;-;+15555550123", "poll-guid", null);
    ToolContext context = toolContext(message, null);

    String output =
        new ReadPollAgentTool(wrapper)
            .getTool()
            .handler()
            .apply(context, mapper.createObjectNode());

    assertEquals(List.of("poll-guid", "message-guid"), wrapper.readPollGuids);
    assertTrue(output.contains("could not read poll"));
    assertTrue(output.contains("poll-guid"));
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
        pollBundleId(),
        associatedMessageGuid,
        replyToGuid,
        false);
  }

  private static IncomingMessage plainIncomingMessage(
      String chatGuid, String messageGuid, String text) {
    return plainIncomingMessage(chatGuid, messageGuid, text, null);
  }

  private static IncomingMessage plainIncomingMessage(
      String chatGuid, String messageGuid, String text, String replyToGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        messageGuid,
        null,
        text,
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        false,
        Instant.now(),
        List.of(),
        null,
        null,
        replyToGuid,
        false);
  }

  private static ApiV1ChatChatGuidMessageGet200ResponseDataInner historyMessage(
      String guid, String balloonBundleId, String associatedMessageGuid) {
    return new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
        .guid(guid)
        .balloonBundleId(balloonBundleId)
        .associatedMessageGuid(associatedMessageGuid);
  }

  private static String pollBundleId() {
    return "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls";
  }

  private static final class CapturingBBHttpClientWrapper extends BBHttpClientWrapper {
    private final ObjectMapper mapper;
    private String lastPollChatGuid;
    private String lastPollTitle;
    private List<String> lastPollOptionTexts;
    private String lastReadPollGuid;
    private String lastHistoryChatGuid;
    private final List<String> readPollGuids = new java.util.ArrayList<>();
    private List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> historyMessages = List.of();
    private final Set<String> failingReadPollGuids = new HashSet<>();
    private JsonNode pollReadResponse;
    private boolean failReadPoll;

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
      this.readPollGuids.add(messageGuid);
      if (failReadPoll || failingReadPollGuids.contains(messageGuid)) {
        throw new IllegalStateException("BlueBubbles failed");
      }
      if (pollReadResponse != null) {
        return pollReadResponse;
      }
      return mapper.createObjectNode().put("messageGuid", messageGuid).put("title", "Lunch?");
    }

    @Override
    public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(
        String chatGuid) {
      this.lastHistoryChatGuid = chatGuid;
      return historyMessages;
    }
  }
}
