package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.Response.ToolChoice;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ToolChoiceOptions;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderAuthAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.coder.StartCoderAsyncTaskAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    AgentWorkflowProperties workflowProperties = new AgentWorkflowProperties();

    when(openAIClient.responses()).thenReturn(responseService);

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            mem0Client,
            gcalClient,
            giphyClient,
            new InMemoryAgentSettingsStore(),
            workflowProperties,
            null,
            new ModelPicker());

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
  void recordsCurrentUserTurnBeforeAssistantTurn() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-order";
    ConversationState state = new ConversationState();
    agent.getConversations().put(chatGuid, state);
    IncomingMessage incoming =
        incomingMessage(chatGuid, "msg-order-1", "can you check this?", 1_000L);

    agent.recordAssistantTurnForCurrentMessage(incoming, "Yep, checking now.", null);

    List<ConversationTurn> history = state.history();
    assertEquals(2, history.size());
    assertEquals("user", history.get(0).role());
    assertEquals("Alice: can you check this?", history.get(0).content());
    assertEquals("assistant", history.get(1).role());
    assertEquals("Yep, checking now.", history.get(1).content());
  }

  @Test
  void recordsCurrentUserTurnOnlyOnceForMultipleAssistantTurns() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-order";
    ConversationState state = new ConversationState();
    agent.getConversations().put(chatGuid, state);
    IncomingMessage incoming = incomingMessage(chatGuid, "msg-order-2", "send both things", 1_000L);

    agent.recordAssistantTurnForCurrentMessage(incoming, "First thing.", null);
    agent.recordAssistantTurnForCurrentMessage(incoming, "Second thing.", null);

    List<ConversationTurn> history = state.history();
    assertEquals(3, history.size());
    assertEquals("user", history.get(0).role());
    assertEquals("assistant", history.get(1).role());
    assertEquals("assistant", history.get(2).role());
  }

  @Test
  void scheduledWorkflowCanSendEvenWhenAnotherWorkflowIsLatest() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-scheduled";
    ConversationState state = new ConversationState();
    state.setLatestWorkflowRunId("newer-live-run");
    agent.getConversations().put(chatGuid, state);
    AgentWorkflowContext scheduledContext =
        new AgentWorkflowContext(
            "scheduled:" + chatGuid + ":follow-up", chatGuid, "scheduled-message", Instant.now());

    assertTrue(agent.canSendResponsesForWorkflowRun(scheduledContext, "scheduled-run"));
  }

  @Test
  void callbackWorkflowCanSendEvenWhenAnotherWorkflowIsLatest() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-callback";
    ConversationState state = new ConversationState();
    state.setLatestWorkflowRunId("newer-live-run");
    agent.getConversations().put(chatGuid, state);
    AgentWorkflowContext callbackContext =
        new AgentWorkflowContext(
            "callback:callback-id", chatGuid, "callback-message", Instant.now());

    assertTrue(agent.canSendResponsesForWorkflowRun(callbackContext, "callback-run"));
  }

  @Test
  void agentInstructionsTellModelToUseAllInOneToolForCoderTasks() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());

    String prompt =
        agent
            .buildConversationInput(
                List.of(),
                incomingMessage("iMessage;+;chat-coder", "msg-coder", "start a coder task", 1_000L))
            .toString();

    assertTrue(prompt.contains(StartCoderAsyncTaskAgentTool.TOOL_NAME));
    assertTrue(prompt.contains("creates the callback"));
    assertTrue(prompt.contains("starts the Coder task"));
    assertTrue(prompt.contains("schedule_event"));
    assertTrue(prompt.contains("still pending or running"));
    assertTrue(prompt.contains("do not call create_workflow_callback"));
  }

  @Test
  void olderLiveWorkflowCannotSendWhenAnotherWorkflowIsLatest() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-live";
    ConversationState state = new ConversationState();
    state.setLatestWorkflowRunId("newer-live-run");
    agent.getConversations().put(chatGuid, state);
    AgentWorkflowContext liveContext =
        new AgentWorkflowContext(chatGuid, chatGuid, "message", Instant.now());

    assertFalse(agent.canSendResponsesForWorkflowRun(liveContext, "older-live-run"));
  }

  @Test
  void dropsHydratedOlderUpdatedMessageInsteadOfSendingItToLlm() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    String chatGuid = "iMessage;+;chat-stale";

    when(openAIClient.responses()).thenReturn(responseService);
    bbHttpClientWrapper.setMessages(
        chatGuid,
        List.of(
            blueBubblesMessage(chatGuid, "msg-newer", "newer message", false, 2_000L),
            blueBubblesMessage(chatGuid, "msg-older", "older message", false, 1_000L)));

    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper);

    agent.handleIncomingMessage(incomingMessage(chatGuid, "msg-older", "older message", 1_000L));

    verify(responseService, never()).create(any(ResponseCreateParams.class));
  }

  @Test
  void dropsRepeatedIncomingGuid() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    String chatGuid = "iMessage;+;chat-duplicate";

    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper);
    IncomingMessage incoming =
        incomingMessage(chatGuid, "msg-duplicate", "please only process once", 1_000L);

    agent.handleIncomingMessage(incoming);
    agent.handleIncomingMessage(incoming);

    verify(responseService, times(1)).create(any(ResponseCreateParams.class));
  }

  @Test
  void hydratesConversationHistoryOldestToNewestWithSenderSummaries() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    String chatGuid = "iMessage;+;chat-hydrate";
    bbHttpClientWrapper.setMessages(
        chatGuid,
        List.of(
            blueBubblesMessage(chatGuid, "msg-current", "current", false, 3_000L),
            blueBubblesMessage(chatGuid, "msg-assistant", "assistant reply", true, 2_000L),
            blueBubblesMessage(chatGuid, "msg-user", "older user text", false, 1_000L)));
    BBMessageAgent agent = newAgent(Mockito.mock(OpenAIClient.class), bbHttpClientWrapper);

    ConversationState state =
        agent.computeConversationState(
            chatGuid, incomingMessage(chatGuid, "msg-current", "current", 3_000L));

    List<ConversationTurn> history = state.history();
    assertEquals(2, history.size());
    assertEquals("user", history.get(0).role());
    assertEquals("Alice: older user text", history.get(0).content());
    assertEquals("assistant", history.get(1).role());
    assertEquals("assistant reply", history.get(1).content());
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
            null,
            new ModelPicker());

    IncomingMessage incoming =
        new IncomingMessage(
            "iMessage;+;chat-group-1",
            "msg-group-1",
            null,
            "please rename this chat",
            false,
            "iMessage",
            "Alice",
            true,
            Instant.now(),
            List.of(),
            false);

    agent.handleIncomingMessage(incoming);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    assertTrue(paramsCaptor.getValue().toString().contains(RenameConversationAgentTool.TOOL_NAME));
  }

  @Test
  void injectsCoderMcpToolsAlongsideAuthToolWhenLinked() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    CoderMcpClient coderMcpClient = Mockito.mock(CoderMcpClient.class);

    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    when(coderMcpClient.getAgentTools(eq("Alice"), anySet()))
        .thenReturn(List.of(testTool("coder__coder_list_templates_abc123")));

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            coderMcpClient,
            null,
            null,
            Mockito.mock(GiphyClient.class),
            new InMemoryAgentSettingsStore(),
            new AgentWorkflowProperties(),
            null,
            new ModelPicker());

    agent.handleIncomingMessage(
        incomingMessage("iMessage;+;chat-coder", "msg-coder-tools", "what coder tools?", 1_000L));

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    String request = paramsCaptor.getValue().toString();
    assertTrue(request.contains(CoderAuthAgentTool.TOOL_NAME));
    assertTrue(request.contains("coder__coder_list_templates_abc123"));
  }

  @Test
  void injectsAllInOneCoderTaskToolAndHidesDirectCreateTaskTool() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    CoderMcpClient coderMcpClient = Mockito.mock(CoderMcpClient.class);

    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    when(coderMcpClient.getAgentTools(eq("Alice"), anySet()))
        .thenReturn(List.of(testTool("coder__coder_list_templates_abc123")));

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            coderMcpClient,
            Mockito.mock(WorkflowCallbackService.class),
            null,
            Mockito.mock(GiphyClient.class),
            new InMemoryAgentSettingsStore(),
            new AgentWorkflowProperties(),
            null,
            new ModelPicker());

    agent.handleIncomingMessage(
        incomingMessage("iMessage;+;chat-coder", "msg-coder-task", "start a coder task", 1_000L));

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    String request = paramsCaptor.getValue().toString();
    assertTrue(request.contains(StartCoderAsyncTaskAgentTool.TOOL_NAME));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> hiddenToolsCaptor = ArgumentCaptor.forClass((Class) Set.class);
    verify(coderMcpClient).getAgentTools(eq("Alice"), hiddenToolsCaptor.capture());
    assertTrue(
        hiddenToolsCaptor.getValue().contains(StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL));
  }

  @Test
  void renameConversationToolRenamesGroupChats() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();

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
            true,
            Instant.now(),
            List.of(),
            false);

    BBMessageAgent bbMessageAgent = Mockito.mock(BBMessageAgent.class);
    when(bbMessageAgent.getObjectMapper())
        .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
    when(bbMessageAgent.canSendResponses(any())).thenReturn(true);
    ToolContext context = new ToolContext(bbMessageAgent, groupMessage, null);
    com.fasterxml.jackson.databind.node.ObjectNode args =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    args.put("name", "New Group Name");

    String output = tool.handler().apply(context, args);

    assertEquals("renamed", output);
    assertEquals("iMessage;+;chat-group-1", bbHttpClientWrapper.renamedChatGuid);
    assertEquals("New Group Name", bbHttpClientWrapper.renamedDisplayName);
  }

  @Test
  void renameConversationToolRejectsNonGroupChats() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();

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
    assertEquals(0, bbHttpClientWrapper.renameCalls);
  }

  private static BBMessageAgent newAgent(
      OpenAIClient openAIClient, BBHttpClientWrapper bbHttpClientWrapper) {
    return new BBMessageAgent(
        openAIClient,
        bbHttpClientWrapper,
        Mockito.mock(Mem0Client.class),
        Mockito.mock(GcalClient.class),
        Mockito.mock(GiphyClient.class),
        new InMemoryAgentSettingsStore(),
        new AgentWorkflowProperties(),
        null,
        new ModelPicker());
  }

  private static class StubBBHttpClientWrapper extends BBHttpClientWrapper {
    private final Map<String, List<ApiV1ChatChatGuidMessageGet200ResponseDataInner>> messages =
        new ConcurrentHashMap<>();
    private int renameCalls;
    private String renamedChatGuid;
    private String renamedDisplayName;
    private boolean renameResult = true;

    StubBBHttpClientWrapper() {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
    }

    void setMessages(
        String chatGuid, List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages) {
      this.messages.put(chatGuid, messages);
    }

    @Override
    public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(
        String chatGuid) {
      return messages.getOrDefault(chatGuid, List.of());
    }

    @Override
    public void sendTextDirect(IncomingMessage message, String text) {}

    @Override
    public void sendTextDirect(ApiV1MessageTextPostRequest request) {}

    @Override
    public boolean sendReactionDirect(ApiV1MessageReactPostRequest request) {
      return true;
    }

    @Override
    public boolean sendMultipartMessage(
        String chatGuid, String message, List<AttachmentData> attachments) {
      return true;
    }

    @Override
    public boolean renameConversation(String chatGuid, String displayName) {
      renameCalls++;
      renamedChatGuid = chatGuid;
      renamedDisplayName = displayName;
      return renameResult;
    }
  }

  private static IncomingMessage incomingMessage(
      String chatGuid, String messageGuid, String text, long epochSecond) {
    return new IncomingMessage(
        chatGuid,
        messageGuid,
        null,
        text,
        false,
        "iMessage",
        "Alice",
        false,
        Instant.ofEpochSecond(epochSecond),
        List.of(),
        false);
  }

  private static ApiV1ChatChatGuidMessageGet200ResponseDataInner blueBubblesMessage(
      String chatGuid, String messageGuid, String text, boolean fromMe, long epochSecond) {
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle handle =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle().address("Alice");
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner().guid(chatGuid);
    return new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
        .guid(messageGuid)
        .text(text)
        .isFromMe(fromMe)
        .handle(handle)
        .chats(List.of(chat))
        .dateCreated(epochSecond);
  }

  private static Response responseWithFunctionCall(String name, String argsJson, String callId) {
    ResponseFunctionToolCall call =
        ResponseFunctionToolCall.builder().name(name).arguments(argsJson).callId(callId).build();
    return baseResponse(List.of(ResponseOutputItem.ofFunctionCall(call)));
  }

  private static AgentTool testTool(String name) {
    Map<String, Object> properties = new LinkedHashMap<>();
    FunctionTool.Parameters parameters =
        FunctionTool.Parameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .build();
    return new AgentTool(name, "test tool", parameters, false, (context, args) -> "ok");
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
