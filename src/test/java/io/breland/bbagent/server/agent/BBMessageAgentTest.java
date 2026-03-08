package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.Response.ToolChoice;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ToolChoiceOptions;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BBMessageAgentTest {

  @Test
  void handlesSimpleTextConversationEndToEnd() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    V1ContactApi contactApi = Mockito.mock(V1ContactApi.class);
    Mem0Client mem0Client = Mockito.mock(Mem0Client.class);
    GcalClient gcalClient = Mockito.mock(GcalClient.class);
    GiphyClient giphyClient = Mockito.mock(GiphyClient.class);
    AgentWorkflowProperties workflowProperties = new AgentWorkflowProperties();

    when(openAIClient.responses()).thenReturn(responseService);
    when(messageApi.apiV1MessageTextPost(anyString(), any())).thenReturn(Mono.empty());

    BBHttpClientWrapper bbHttpClientWrapper = new BBHttpClientWrapper("pw", messageApi, contactApi);

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            mem0Client,
            gcalClient,
            giphyClient,
            new InMemoryAgentSettingsStore(),
            workflowProperties,
            null);

    Response first =
        responseWithFunctionCall(
            "send_text",
            "{\"chatGuid\":\"iMessage;+;chat-1\",\"message\":\"Hey! Doing well—how about you?\"}",
            "call-1");
    Response second = responseWithNoToolCalls();

    when(responseService.create(any(ResponseCreateParams.class))).thenReturn(first, second);

    IncomingMessage incoming =
        new IncomingMessage(
            "iMessage;+;chat-1",
            "msg-1",
            null,
            "how how's it going",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    agent.handleIncomingMessage(incoming);

    ArgumentCaptor<ApiV1MessageTextPostRequest> bodyCaptor =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);
    verify(messageApi, times(1)).apiV1MessageTextPost(eq("pw"), bodyCaptor.capture());
    ApiV1MessageTextPostRequest body = bodyCaptor.getValue();
    assertEquals("iMessage;+;chat-1", body.getChatGuid());
    assertEquals("Hey! Doing well—how about you?", body.getMessage());
    verify(responseService, times(2)).create(any(ResponseCreateParams.class));
  }

  @Test
  void reactionMessageMatcherCoversReactedEmojiTo() {
    assertTrue(BBMessageAgent.isReactionMessage("Reacted 😂 to"));
    assertTrue(BBMessageAgent.isReactionMessage("  Reacted 😂 to"));
  }

  @Test
  void reactionMessageMatcherCoversCommonPrefixes() {
    assertTrue(BBMessageAgent.isReactionMessage("Loved \"nice!\""));
    assertTrue(BBMessageAgent.isReactionMessage("Liked it"));
    assertTrue(BBMessageAgent.isReactionMessage("Disliked that"));
    assertTrue(BBMessageAgent.isReactionMessage("Questioned \"why?\""));
    assertTrue(BBMessageAgent.isReactionMessage("Emphasized wow"));
    assertTrue(BBMessageAgent.isReactionMessage("Laughed at that"));
  }

  @Test
  void reactionMessageMatcherIgnoresNormalText() {
    assertFalse(BBMessageAgent.isReactionMessage("Reacted? I don't think so."));
    assertFalse(BBMessageAgent.isReactionMessage("I loved that"));
    assertFalse(BBMessageAgent.isReactionMessage("questioned"));
    assertFalse(BBMessageAgent.isReactionMessage(""));
    assertFalse(BBMessageAgent.isReactionMessage(null));
  }

  @Test
  void includesRenameConversationToolForGroupChats() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    V1ContactApi contactApi = Mockito.mock(V1ContactApi.class);
    Mem0Client mem0Client = Mockito.mock(Mem0Client.class);
    GcalClient gcalClient = Mockito.mock(GcalClient.class);
    GiphyClient giphyClient = Mockito.mock(GiphyClient.class);
    AgentWorkflowProperties workflowProperties = new AgentWorkflowProperties();

    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    when(messageApi.apiV1MessageTextPost(anyString(), any())).thenReturn(Mono.empty());

    BBHttpClientWrapper bbHttpClientWrapper = new BBHttpClientWrapper("pw", messageApi, contactApi);
    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            mem0Client,
            gcalClient,
            giphyClient,
            new InMemoryAgentSettingsStore(),
            workflowProperties,
            null);

    IncomingMessage incoming =
        new IncomingMessage(
            "iMessage;+;chat-group-1",
            "msg-group-1",
            null,
            "please rename this chat",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    agent.handleIncomingMessage(incoming);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    boolean hasRenameTool =
        paramsCaptor.getValue().tools().stream()
            .filter(tool -> tool.getFirst().function().isPresent())
            .map(tool -> tool.getFirst().function().get().name())
            .anyMatch(RenameConversationAgentTool.TOOL_NAME::equals);

    assertTrue(hasRenameTool);
  }

  @Test
  void renameConversationToolRenamesGroupChats() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    V1ContactApi contactApi = Mockito.mock(V1ContactApi.class);
    BBHttpClientWrapper bbHttpClientWrapper =
        Mockito.spy(new BBHttpClientWrapper("pw", messageApi, contactApi));
    when(bbHttpClientWrapper.renameConversation("iMessage;+;chat-group-1", "New Group Name"))
        .thenReturn(true);

    RenameConversationAgentTool toolProvider = new RenameConversationAgentTool(bbHttpClientWrapper);
    var tool = toolProvider.getTool();

    IncomingMessage groupMessage =
        new IncomingMessage(
            "iMessage;+;chat-group-1",
            "msg-group-2",
            null,
            "rename this group",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    ToolContext context = new ToolContext(Mockito.mock(BBMessageAgent.class), groupMessage, null);
    com.fasterxml.jackson.databind.node.ObjectNode args =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    args.put("name", "New Group Name");

    String output = tool.handler().apply(context, args);

    assertEquals("renamed", output);
    verify(bbHttpClientWrapper).renameConversation("iMessage;+;chat-group-1", "New Group Name");
  }

  @Test
  void renameConversationToolRejectsNonGroupChats() {
    V1MessageApi messageApi = Mockito.mock(V1MessageApi.class);
    V1ContactApi contactApi = Mockito.mock(V1ContactApi.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);

    RenameConversationAgentTool toolProvider = new RenameConversationAgentTool(bbHttpClientWrapper);
    var tool = toolProvider.getTool();

    IncomingMessage directMessage =
        new IncomingMessage(
            "iMessage;+;user-1",
            "msg-direct-1",
            null,
            "rename this chat",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    ToolContext context = new ToolContext(Mockito.mock(BBMessageAgent.class), directMessage, null);
    com.fasterxml.jackson.databind.node.ObjectNode args =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    args.put("name", "Should Not Rename");

    String output = tool.handler().apply(context, args);

    assertEquals("not group", output);
    verify(bbHttpClientWrapper, never()).renameConversation(anyString(), anyString());
  }

  private static Response responseWithFunctionCall(String name, String argsJson, String callId) {
    ResponseFunctionToolCall call =
        ResponseFunctionToolCall.builder().name(name).arguments(argsJson).callId(callId).build();
    return baseResponse(List.of(ResponseOutputItem.ofFunctionCall(call)));
  }

  private static Response responseWithNoToolCalls() {
    return baseResponse(List.of());
  }

  private static Response baseResponse(List<ResponseOutputItem> outputItems) {
    return Response.builder()
        .id("resp-1")
        .createdAt(0.0)
        .error((ResponseError) null)
        .incompleteDetails((Response.IncompleteDetails) null)
        .instructions((Response.Instructions) null)
        .metadata((Response.Metadata) null)
        .model(ChatModel.GPT_5_CHAT_LATEST)
        .output(outputItems)
        .parallelToolCalls(false)
        .temperature(0.2)
        .toolChoice(ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
        .tools(List.of())
        .topP(1.0)
        .build();
  }

  private static class InMemoryAgentSettingsStore implements AgentSettingsStore {
    private final Map<String, BBMessageAgent.AssistantResponsiveness> responsivenessByChat =
        new ConcurrentHashMap<>();
    private final Map<String, String> globalNames = new ConcurrentHashMap<>();

    @Override
    public Optional<BBMessageAgent.AssistantResponsiveness> findAssistantResponsiveness(
        String chatGuid) {
      return Optional.ofNullable(responsivenessByChat.get(chatGuid));
    }

    @Override
    public void saveAssistantResponsiveness(
        String chatGuid, BBMessageAgent.AssistantResponsiveness value) {
      responsivenessByChat.put(chatGuid, value);
    }

    @Override
    public void deleteAssistantResponsiveness(String chatGuid) {
      responsivenessByChat.remove(chatGuid);
    }

    @Override
    public Optional<String> findGlobalName(String sender) {
      return Optional.ofNullable(globalNames.get(sender));
    }

    @Override
    public void saveGlobalName(String sender, String name) {
      globalNames.put(sender, name);
    }

    @Override
    public void deleteGlobalName(String sender) {
      globalNames.remove(sender);
    }
  }
}
