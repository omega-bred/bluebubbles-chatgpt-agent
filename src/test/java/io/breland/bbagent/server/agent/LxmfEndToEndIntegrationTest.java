package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.location.ReverseLocationLookup;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AgentSettingsStore;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.ReadPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SearchConvoHistoryAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.tools.search.ToolSearchAgentTool;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.lxmf.LxmfBridgeClient;
import io.breland.bbagent.server.agent.transport.lxmf.LxmfMessageTransport;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LxmfEndToEndIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final List<String> LXMF_FILTERED_TOOL_NAMES =
      List.of(
          CurrentConversationInfoAgentTool.TOOL_NAME,
          GetThreadContextAgentTool.TOOL_NAME,
          ReadPollAgentTool.TOOL_NAME,
          RenameConversationAgentTool.TOOL_NAME,
          SearchConvoHistoryAgentTool.TOOL_NAME,
          SendGiphyAgentTool.TOOL_NAME,
          SendPollAgentTool.TOOL_NAME,
          SendReactionAgentTool.TOOL_NAME);

  @Test
  void lxmfPlainTextTurnBuildsLxmfPromptAndSendsThroughBridgeTransport() {
    LxmfE2eHarness harness = new LxmfE2eHarness();
    harness.enqueueModelResponse(responseWithText("CANARY_OK local lxmf e2e"));

    LxmfTurnResult result = harness.runTurn("AABBCCDD", "BBAGENT_LXMF_CANARY_V1 say hello");

    assertEquals(IncomingMessage.TRANSPORT_LXMF, result.message().metricTransport());
    assertEquals(List.of(new BridgeSend("aabbccdd", "CANARY_OK local lxmf e2e")), harness.sent());
    assertEquals(List.of(ToolSearchAgentTool.TOOL_NAME), toolNames(harness.request(0)));
    List<ResponseInputItem> firstInput = inputItems(harness.request(0));
    assertTrue(inputContains(firstInput, "You are a chat assistant over LXMF on Reticulum"));
    assertTrue(inputContains(firstInput, "one-on-one plain text only"));
    assertTrue(inputContains(firstInput, "All outgoing LXMF text must be plain text only"));
    assertTrue(inputContains(firstInput, "Incoming message from aabbccdd"));
  }

  @Test
  void lxmfCanDiscoverSendTextThenDeliverToolMessageThroughTransport() {
    LxmfE2eHarness harness = new LxmfE2eHarness();
    harness.enqueueModelResponse(
        responseWithFunctionCall(
            ToolSearchAgentTool.TOOL_NAME,
            "{\"query\":\"send a plain text reply via the current chat transport\",\"maxResults\":5}",
            "call-search-send-text"));
    harness.enqueueModelResponse(
        responseWithFunctionCall(
            SendTextAgentTool.TOOL_NAME,
            "{\"message\":\"Tool-discovered LXMF reply\"}",
            "call-send-text"));
    harness.enqueueModelResponse(responseWithText(BBMessageAgent.NO_RESPONSE_TEXT));

    LxmfTurnResult result = harness.runTurn("00112233", "send me a second message");

    assertTrue(result.toolOutputs().contains("sent"));
    assertEquals(List.of(new BridgeSend("00112233", "Tool-discovered LXMF reply")), harness.sent());
    assertEquals(List.of(ToolSearchAgentTool.TOOL_NAME), toolNames(harness.request(0)));
    assertTrue(toolNames(harness.request(1)).contains(ToolSearchAgentTool.TOOL_NAME));
    assertTrue(toolNames(harness.request(1)).contains(SendTextAgentTool.TOOL_NAME));
  }

  @Test
  void lxmfToolSearchFiltersBlueBubblesOnlyCapabilities() {
    LxmfE2eHarness harness = new LxmfE2eHarness();
    harness.enqueueModelResponse(
        responseWithFunctionCall(
            ToolSearchAgentTool.TOOL_NAME,
            "{\"query\":\"create poll react with tapback send gif rename group thread context\","
                + "\"maxResults\":10}",
            "call-search-bluebubbles-only"));
    harness.enqueueModelResponse(responseWithText("LXMF can only do plain text here."));

    LxmfTurnResult result = harness.runTurn("ffeeddcc", "make a poll");

    String searchOutput = result.toolOutputs().getFirst();
    List<String> secondRequestToolNames = toolNames(harness.request(1));
    for (String toolName : LXMF_FILTERED_TOOL_NAMES) {
      assertFalse(searchOutput.contains(toolName));
      assertFalse(secondRequestToolNames.contains(toolName));
    }
    assertEquals(
        List.of(new BridgeSend("ffeeddcc", "LXMF can only do plain text here.")), harness.sent());
  }

  private static List<ResponseInputItem> inputItems(ResponseCreateParams params) {
    return params.input().orElseThrow().asResponse();
  }

  private static boolean inputContains(List<ResponseInputItem> inputItems, String text) {
    return inputItems.stream()
        .map(LxmfEndToEndIntegrationTest::extractText)
        .anyMatch(value -> value.contains(text));
  }

  private static String extractText(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      EasyInputMessage.Content content = item.asEasyInputMessage().content();
      if (content.isTextInput()) {
        return content.asTextInput();
      }
      if (content.isResponseInputMessageContentList()) {
        return content.asResponseInputMessageContentList().toString();
      }
    }
    if (item.isMessage()) {
      return item.asMessage().content().toString();
    }
    return item.toString();
  }

  private static List<String> toolNames(ResponseCreateParams params) {
    return params.tools().orElse(List.of()).stream()
        .filter(Tool::isFunction)
        .map(tool -> tool.asFunction().name())
        .toList();
  }

  private static Response responseWithFunctionCall(String name, String argsJson, String callId) {
    ResponseFunctionToolCall call =
        ResponseFunctionToolCall.builder().name(name).arguments(argsJson).callId(callId).build();
    return baseResponse(List.of(ResponseOutputItem.ofFunctionCall(call)));
  }

  private static Response responseWithText(String text) {
    try {
      String responseJson =
          """
          {
            "id": "resp-1",
            "created_at": 0,
            "model": "gpt-5-chat-latest",
            "output": [
              {
                "type": "message",
                "id": "msg-1",
                "role": "assistant",
                "status": "completed",
                "content": [
                  {
                    "type": "output_text",
                    "text": %s,
                    "annotations": []
                  }
                ]
              }
            ],
            "parallel_tool_calls": false,
            "temperature": 0.2,
            "tools": [],
            "top_p": 1.0
          }
          """
              .formatted(OBJECT_MAPPER.writeValueAsString(text));
      return OBJECT_MAPPER.readValue(responseJson, Response.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to build test response payload", e);
    }
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
        .toolChoice(ToolChoiceOptions.AUTO)
        .tools(List.of())
        .topP(1.0)
        .build();
  }

  private record BridgeSend(String destinationHash, String text) {}

  private record LxmfTurnResult(
      IncomingMessage message, List<ResponseInputItem> finalInputItems, List<String> toolOutputs) {}

  private static final class LxmfE2eHarness {
    private static final int MAX_TOOL_LOOPS = 8;

    private final Queue<Response> queuedResponses = new ArrayDeque<>();
    private final List<ResponseCreateParams> requests = new ArrayList<>();
    private final List<BridgeSend> sent = new ArrayList<>();
    private final OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    private final com.openai.services.blocking.ResponseService responseService =
        Mockito.mock(com.openai.services.blocking.ResponseService.class);
    private final LxmfBridgeClient bridgeClient = Mockito.mock(LxmfBridgeClient.class);
    private final StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    private final AgentProfileService profileService =
        new AgentProfileService(new InMemoryAgentSettingsStore(), null);
    private final AgentPromptBuilder promptBuilder;
    private final BBMessageAgent agent;

    private LxmfE2eHarness() {
      when(openAIClient.responses()).thenReturn(responseService);
      when(responseService.create(any(ResponseCreateParams.class)))
          .thenAnswer(
              invocation -> {
                requests.add(invocation.getArgument(0));
                Response response = queuedResponses.poll();
                if (response == null) {
                  throw new AssertionError(
                      "No queued model response for request " + requests.size());
                }
                return response;
              });
      when(bridgeClient.sendText(anyString(), anyString()))
          .thenAnswer(
              invocation -> {
                sent.add(new BridgeSend(invocation.getArgument(0), invocation.getArgument(1)));
                return true;
              });
      AgentAttachmentInputBuilder attachmentInputBuilder =
          new AgentAttachmentInputBuilder(bbHttpClientWrapper);
      MessageTransportRegistry transportRegistry =
          new MessageTransportRegistry(List.of(new LxmfMessageTransport(bridgeClient)));
      this.promptBuilder =
          new AgentPromptBuilder(
              bbHttpClientWrapper,
              ReverseLocationLookup.noop(),
              profileService,
              attachmentInputBuilder,
              null,
              null);
      this.agent =
          new BBMessageAgent(
              openAIClient,
              bbHttpClientWrapper,
              Mockito.mock(Mem0Client.class),
              Mockito.mock(GcalClient.class),
              null,
              Mockito.mock(GiphyClient.class),
              profileService,
              attachmentInputBuilder,
              transportRegistry,
              OBJECT_MAPPER,
              Mockito.mock(CadenceWorkflowLauncher.class),
              null,
              null,
              null,
              new ModelPicker());
    }

    private void enqueueModelResponse(Response response) {
      queuedResponses.add(response);
    }

    private LxmfTurnResult runTurn(String sourceHash, String text) {
      IncomingMessage message = lxmfMessage(sourceHash, text);
      AgentWorkflowContext workflowContext =
          new AgentWorkflowContext(
              message.chatGuid(), message.chatGuid(), message.messageGuid(), Instant.now());
      ConversationState state =
          agent
              .getConversations()
              .computeIfAbsent(message.chatGuid(), ignored -> new ConversationState());
      state.setLatestWorkflowMessageGuid(message.messageGuid());
      List<ResponseInputItem> inputItems =
          new ArrayList<>(promptBuilder.buildConversationInput(List.of(), List.of(), message));
      List<String> toolOutputs = new ArrayList<>();
      Response response = agent.createResponse(inputItems, message, workflowContext);
      boolean sentTextByTool = false;
      int toolLoops = 0;
      while (response != null) {
        List<ResponseFunctionToolCall> functionCalls =
            AgentResponseHelper.extractFunctionCalls(response);
        if (functionCalls.isEmpty()) {
          sendFinalAssistantTextIfNeeded(response, message, workflowContext, sentTextByTool);
          return new LxmfTurnResult(message, List.copyOf(inputItems), List.copyOf(toolOutputs));
        }
        assertTrue(toolLoops++ < MAX_TOOL_LOOPS, "LXMF e2e tool loop exceeded " + MAX_TOOL_LOOPS);
        inputItems.addAll(AgentResponseHelper.extractToolContextItems(response, functionCalls));
        for (ResponseFunctionToolCall functionCall : functionCalls) {
          if (SendTextAgentTool.TOOL_NAME.equals(functionCall.name())) {
            sentTextByTool = true;
          }
          ResponseInputItem output = agent.runToolActivity(functionCall, message, workflowContext);
          recordToolOutput(output, toolOutputs);
          inputItems.add(output);
        }
        response = agent.createResponse(inputItems, message, workflowContext);
      }
      return new LxmfTurnResult(message, List.copyOf(inputItems), List.copyOf(toolOutputs));
    }

    private void sendFinalAssistantTextIfNeeded(
        Response response,
        IncomingMessage message,
        AgentWorkflowContext workflowContext,
        boolean sentTextByTool) {
      String assistantText =
          AgentResponseHelper.normalizeAssistantText(
              OBJECT_MAPPER, AgentResponseHelper.extractResponseText(response));
      if (assistantText.isBlank()
          || BBMessageAgent.NO_RESPONSE_TEXT.equalsIgnoreCase(assistantText)
          || sentTextByTool) {
        return;
      }
      boolean sentFinalText = agent.sendThreadAwareText(message, assistantText, workflowContext);
      if (sentFinalText) {
        agent.recordAssistantTurnForCurrentMessage(message, assistantText, workflowContext);
      }
    }

    private void recordToolOutput(ResponseInputItem output, List<String> toolOutputs) {
      if (output == null || !output.isFunctionCallOutput()) {
        return;
      }
      ResponseInputItem.FunctionCallOutput functionCallOutput = output.asFunctionCallOutput();
      if (functionCallOutput.output().isString()) {
        toolOutputs.add(functionCallOutput.output().asString());
      }
    }

    private ResponseCreateParams request(int index) {
      return requests.get(index);
    }

    private List<BridgeSend> sent() {
      return List.copyOf(sent);
    }

    private IncomingMessage lxmfMessage(String sourceHash, String text) {
      String normalizedSourceHash = sourceHash == null ? null : sourceHash.trim().toLowerCase();
      return new IncomingMessage(
          IncomingMessage.TRANSPORT_LXMF,
          IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_LXMF, normalizedSourceHash),
          "msg-" + normalizedSourceHash,
          null,
          text,
          false,
          "LXMF",
          normalizedSourceHash,
          false,
          Instant.now(),
          List.of(),
          false);
    }
  }

  private static final class StubBBHttpClientWrapper extends BBHttpClientWrapper {
    private StubBBHttpClientWrapper() {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
    }
  }

  private static final class InMemoryAgentSettingsStore implements AgentSettingsStore {
    private final Map<String, AssistantResponsiveness> responsivenessByChat =
        new ConcurrentHashMap<>();
    private final Map<String, String> globalNames = new ConcurrentHashMap<>();

    @Override
    public Optional<AssistantResponsiveness> findAssistantResponsiveness(String chatGuid) {
      return Optional.ofNullable(responsivenessByChat.get(chatGuid));
    }

    @Override
    public void saveAssistantResponsiveness(String chatGuid, AssistantResponsiveness value) {
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
