package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ToolChoiceOptions;
import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.location.ReverseLocationLookup;
import io.breland.bbagent.server.agent.location.ReverseLocationLookupResult;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AgentSettingsStore;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BBMessageAgentTest {
  private static final String DEVELOPER_PROMPT_MARKER = "Only call send_text";

  @Test
  void dispatchesIncomingTextToCadenceWorkflow() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    CadenceWorkflowLauncher cadenceWorkflowLauncher = Mockito.mock(CadenceWorkflowLauncher.class);
    WorkflowExecution execution = new WorkflowExecution();
    execution.setRunId("run-simple");

    when(openAIClient.responses()).thenReturn(responseService);
    when(cadenceWorkflowLauncher.startWorkflow(any(CadenceMessageWorkflowRequest.class)))
        .thenReturn(execution);
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, cadenceWorkflowLauncher);

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

    ArgumentCaptor<CadenceMessageWorkflowRequest> requestCaptor =
        ArgumentCaptor.forClass(CadenceMessageWorkflowRequest.class);
    verify(cadenceWorkflowLauncher).startWorkflow(requestCaptor.capture());
    assertEquals("iMessage;+;chat-1", requestCaptor.getValue().workflowContext().workflowId());
    assertEquals("msg-1", requestCaptor.getValue().workflowContext().messageGuid());
    verify(responseService, never()).create(any(ResponseCreateParams.class));
  }

  @Test
  void blocksNormalProcessingUntilTermsAccepted() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
    AgentAccountEntity account = account("account-terms", null);
    when(accountResolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(Optional.of(new AgentAccountResolver.ResolvedAccount(account, List.of())));
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, accountResolver);

    agent.handleIncomingMessage(
        incomingMessage("iMessage;+;chat-terms", "msg-terms-1", "what can you do?", 1_000L));

    verify(responseService, never()).create(any(ResponseCreateParams.class));
    assertEquals(1, bbHttpClientWrapper.sentTexts.size());
    assertTrue(bbHttpClientWrapper.sentTexts.getFirst().contains("Terms of Use"));
    assertTrue(bbHttpClientWrapper.sentTexts.getFirst().contains("Reply YES"));
  }

  @Test
  void acceptsTermsWhenUserRepliesYes() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
    AgentAccountEntity pendingAccount = account("account-terms", null);
    AgentAccountEntity acceptedAccount = account("account-terms", Instant.now());
    when(accountResolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(pendingAccount, List.of())));
    when(accountResolver.acceptTerms(any(IncomingMessage.class)))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(acceptedAccount, List.of())));
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, accountResolver);
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-terms", "msg-terms-yes", "YES", 1_000L);

    agent.handleIncomingMessage(incoming);

    verify(accountResolver).acceptTerms(incoming);
    verify(responseService, never()).create(any(ResponseCreateParams.class));
    assertEquals(List.of(BBMessageAgent.TERMS_ACCEPTED_REPLY), bbHttpClientWrapper.sentTexts);
  }

  @Test
  void dropsBlockedAccountBeforeTermsOrModelProcessing() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
    AgentAccountEntity blockedAccount = account("account-blocked", Instant.now());
    blockedAccount.setProcessingBlocked(true);
    blockedAccount.setProcessingBlockedReason("abuse");
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-blocked", "msg-blocked", "hello?", 1_000L);
    when(accountResolver.resolve(incoming))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(blockedAccount, List.of())));
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, accountResolver);

    agent.handleIncomingMessage(incoming);

    verify(accountResolver).recordMessageIdentities(incoming);
    verify(accountResolver).resolve(incoming);
    verify(accountResolver, never()).resolveOrCreate(any(IncomingMessage.class));
    verify(responseService, never()).create(any(ResponseCreateParams.class));
    assertTrue(bbHttpClientWrapper.sentTexts.isEmpty());
  }

  @Test
  void dropsBlockedPollUpdateBeforeReadingPollState() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
    AgentAccountEntity blockedAccount = account("account-blocked", Instant.now());
    blockedAccount.setProcessingBlocked(true);
    blockedAccount.setProcessingBlockedReason("abuse");
    IncomingMessage incoming =
        pollIncomingMessage("iMessage;+;chat-blocked", "poll-vote-guid", "poll-root-guid");
    when(accountResolver.resolve(incoming))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(blockedAccount, List.of())));
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, accountResolver);

    agent.handleIncomingMessage(incoming);

    verify(accountResolver).recordMessageIdentities(incoming);
    verify(accountResolver).resolve(incoming);
    verify(responseService, never()).create(any(ResponseCreateParams.class));
    assertEquals(0, bbHttpClientWrapper.readPollCalls);
  }

  @Test
  void reactionMessageMatcherCoversReactedEmojiTo() {
    assertTrue(MessageReactionSupport.isReactionMessage("Reacted 😂 to"));
    assertTrue(MessageReactionSupport.isReactionMessage("  Reacted 😂 to"));
  }

  @Test
  void reactionMessageMatcherCoversCommonPrefixes() {
    assertTrue(MessageReactionSupport.isReactionMessage("Loved \"nice!\""));
    assertTrue(MessageReactionSupport.isReactionMessage("Liked it"));
    assertTrue(MessageReactionSupport.isReactionMessage("Disliked that"));
    assertTrue(MessageReactionSupport.isReactionMessage("Questioned \"why?\""));
    assertTrue(MessageReactionSupport.isReactionMessage("Emphasized wow"));
    assertTrue(MessageReactionSupport.isReactionMessage("Laughed at that"));
  }

  @Test
  void reactionMessageMatcherIgnoresNormalText() {
    assertFalse(MessageReactionSupport.isReactionMessage("Reacted? I don't think so."));
    assertFalse(MessageReactionSupport.isReactionMessage("I loved that"));
    assertFalse(MessageReactionSupport.isReactionMessage("questioned"));
    assertFalse(MessageReactionSupport.isReactionMessage(""));
    assertFalse(MessageReactionSupport.isReactionMessage(null));
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
  void agentInstructionsDoNotMentionRetiredAsyncTools() {
    AgentPromptBuilder promptBuilder = promptBuilder(new StubBBHttpClientWrapper());

    String prompt =
        promptBuilder
            .buildConversationInput(
                List.of(),
                List.of(),
                incomingMessage("iMessage;+;chat-prompt", "msg-prompt", "hello", 1_000L))
            .toString();

    assertFalse(prompt.contains("retired__"));
    assertFalse(prompt.contains("retired_auth"));
    assertFalse(prompt.contains("start_retired_async_task"));
  }

  @Test
  void injectsFindMyLocationContextForDirectMessages() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    StubReverseLocationLookup reverseLocationLookup = new StubReverseLocationLookup();
    bbHttpClientWrapper.setFindMyLocation(
        "Alice",
        findMyLocation(
            "+15555550123",
            List.of(37.33182, -122.03118),
            "Apple Park",
            "1 Apple Park Way, Cupertino, CA 95014, United States",
            1777050691000L));
    reverseLocationLookup.setResult("Cupertino, Santa Clara County, California, United States");
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper);
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-location", "msg-location", "where am I?", 1_000L);
    AgentPromptBuilder promptBuilder =
        promptBuilder(bbHttpClientWrapper, null, reverseLocationLookup);

    List<ResponseInputItem> input =
        promptBuilder.buildConversationInput(List.of(), List.of(), incoming);

    ResponseInputItem locationContext = input.get(input.size() - 1);
    assertTrue(isDeveloperEasyInputMessage(locationContext));
    String contextText = extractText(locationContext);
    assertTrue(contextText.contains("Current location context for the current BlueChat sender"));
    assertTrue(contextText.contains("latitude=37.33182"));
    assertTrue(contextText.contains("longitude=-122.03118"));
    assertTrue(
        contextText.contains(
            "reverse_geocoded_approximate_address=Cupertino, Santa Clara County, California, United States"));
    assertTrue(contextText.contains("short_address=Apple Park"));
    assertTrue(contextText.contains("last_updated=2026-04-24T17:11:31Z"));
    assertEquals(1, bbHttpClientWrapper.findMyLookupCalls);
    assertEquals("Alice", bbHttpClientWrapper.lastFindMyUserId);
    assertEquals(1, reverseLocationLookup.reverseLookupCalls);
    assertEquals(37.33182, reverseLocationLookup.lastLatitude);
    assertEquals(-122.03118, reverseLocationLookup.lastLongitude);

    agent.createResponse(input, incoming, null);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    List<ResponseInputItem> requestInput =
        paramsCaptor.getValue().input().orElseThrow().asResponse();
    assertTrue(
        extractText(requestInput.get(0))
            .contains("Current location context for the current BlueChat sender"));
    assertFalse(
        requestInput.subList(1, requestInput.size()).stream().anyMatch(this::isSystemInputMessage));
  }

  @Test
  void injectsFindMySharingHintWhenDirectMessageHasNoLocation() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    AgentPromptBuilder promptBuilder = promptBuilder(bbHttpClientWrapper);
    IncomingMessage incoming =
        incomingMessage(
            "iMessage;+;chat-no-location", "msg-no-location", "what is near me?", 1_000L);

    List<ResponseInputItem> input =
        promptBuilder.buildConversationInput(List.of(), List.of(), incoming);

    ResponseInputItem locationContext = input.get(input.size() - 1);
    assertTrue(isDeveloperEasyInputMessage(locationContext));
    String contextText = extractText(locationContext);
    assertTrue(contextText.contains("No current location is available"));
    assertTrue(contextText.contains("do not guess"));
    assertTrue(contextText.contains("share their location"));
    assertEquals(1, bbHttpClientWrapper.findMyLookupCalls);
    assertEquals("Alice", bbHttpClientWrapper.lastFindMyUserId);
  }

  @Test
  void injectsFindMyLocationContextUsingLinkedWebsiteEmailFallback() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    WebsiteAccountService websiteAccountService = Mockito.mock(WebsiteAccountService.class);
    bbHttpClientWrapper.setFindMyLocation(
        "alice@example.com",
        findMyLocation(
            "alice@example.com",
            List.of(37.33182, -122.03118),
            "Apple Park",
            "1 Apple Park Way, Cupertino, CA 95014, United States",
            1777050691000L));
    when(websiteAccountService.findLinkedAccountEmail(any(IncomingMessage.class)))
        .thenReturn(Optional.of("alice@example.com"));
    AgentPromptBuilder promptBuilder =
        promptBuilder(bbHttpClientWrapper, websiteAccountService, ReverseLocationLookup.noop());
    IncomingMessage incoming =
        incomingMessage(
            "iMessage;+;chat-linked-location", "msg-linked-location", "where am I?", 1_000L);

    List<ResponseInputItem> input =
        promptBuilder.buildConversationInput(List.of(), List.of(), incoming);

    ResponseInputItem locationContext = input.get(input.size() - 1);
    assertTrue(
        extractText(locationContext)
            .contains("Current location context for the current BlueChat sender"));
    assertEquals(List.of("Alice", "alice@example.com"), bbHttpClientWrapper.lastFindMyUserIds);
  }

  @Test
  void doesNotInjectFindMyLocationContextForGroupMessages() {
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    bbHttpClientWrapper.setFindMyLocation(
        "Alice",
        findMyLocation(
            "+15555550123",
            List.of(37.33182, -122.03118),
            "Apple Park",
            "1 Apple Park Way, Cupertino, CA 95014, United States",
            1777050691000L));
    BBMessageAgent agent = newAgent(Mockito.mock(OpenAIClient.class), bbHttpClientWrapper);
    AgentPromptBuilder promptBuilder = promptBuilder(bbHttpClientWrapper);
    IncomingMessage groupMessage =
        new IncomingMessage(
            "iMessage;+;chat-group-location",
            "msg-group-location",
            null,
            "where are we meeting?",
            false,
            "iMessage",
            "Alice",
            true,
            Instant.ofEpochSecond(1_000L),
            List.of(),
            false);

    List<ResponseInputItem> input =
        promptBuilder.buildConversationInput(List.of(), List.of(), groupMessage);

    assertFalse(input.stream().map(this::extractText).anyMatch(text -> text.contains("Find My")));
    assertEquals(0, bbHttpClientWrapper.findMyLookupCalls);
  }

  @Test
  void squashesDeveloperMessagesIntoSystemForWhitelistedModels() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent = newAgent(openAIClient, new StubBBHttpClientWrapper());
    IncomingMessage incoming = incomingMessage("iMessage;+;chat-qwen", "msg-qwen", "hello", 1_000L);

    agent.createResponse(
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(List.of(), List.of(), incoming),
        incoming,
        null);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    List<ResponseInputItem> inputItems = paramsCaptor.getValue().input().orElseThrow().asResponse();
    assertFalse(inputItems.stream().anyMatch(this::isDeveloperEasyInputMessage));
    assertTrue(inputItems.get(0).isEasyInputMessage());
    EasyInputMessage systemMessage = inputItems.get(0).asEasyInputMessage();
    assertEquals(EasyInputMessage.Role.SYSTEM, systemMessage.role());
    String systemText = systemMessage.content().asTextInput();
    assertTrue(systemText.contains("You are a chat assistant for BlueChat."));
    assertTrue(systemText.contains("The public phone number for this agent is +1 (415) 867-4956."));
    assertTrue(systemText.contains(DEVELOPER_PROMPT_MARKER));
  }

  @Test
  void createResponseRecordsLlmCallMetricWithModelTag() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService operationalMetricsService = new OperationalMetricsService(registry);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            null,
            Mockito.mock(GiphyClient.class),
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            Mockito.mock(CadenceWorkflowLauncher.class),
            null,
            null,
            null,
            operationalMetricsService,
            new ModelPicker());
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-llm-metric", "msg-llm-metric", "hello", 1_000L);

    agent.createResponse(
        promptBuilder(bbHttpClientWrapper).buildConversationInput(List.of(), List.of(), incoming),
        incoming,
        null);

    assertEquals(
        1.0,
        registry
            .get("bbagent.agent.llm.call.count")
            .tag("transport", IncomingMessage.METRIC_TRANSPORT_IMESSAGE)
            .tag("operation", "agent_response")
            .tag("model", ModelAccessService.STANDARD_RESPONSES_MODEL)
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .counter()
            .count());
  }

  @Test
  void squashesDeveloperMessagesIntoSystemAfterCadenceJsonRoundTrip() throws Exception {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent = newAgent(openAIClient, new StubBBHttpClientWrapper());
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-qwen-cadence", "msg-qwen-cadence", "hello", 1_000L);
    List<ResponseInputItem> originalInput =
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(List.of(), List.of(), incoming);
    JsonNode inputNode = JsonValue.from(originalInput).convert(JsonNode.class);
    List<ResponseInputItem> roundTrippedInput =
        JsonValue.fromJsonNode(inputNode).convert(new TypeReference<List<ResponseInputItem>>() {});

    agent.createResponse(roundTrippedInput, incoming, null);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    List<ResponseInputItem> inputItems = paramsCaptor.getValue().input().orElseThrow().asResponse();
    JsonNode requestInputNode = JsonValue.from(inputItems).convert(JsonNode.class);
    assertFalse(
        inputItems.stream().anyMatch(this::isDeveloperInputMessage),
        requestInputNode.toPrettyString());
    assertTrue(isSystemInputMessage(inputItems.get(0)), requestInputNode.toPrettyString());
    assertTrue(extractText(inputItems.get(0)).contains(DEVELOPER_PROMPT_MARKER));
  }

  @Test
  void squashesDeveloperMessagesIntoSystemAfterCadenceJsonRoundTripWithHistory() throws Exception {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    BBMessageAgent agent = newAgent(openAIClient, new StubBBHttpClientWrapper());
    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-qwen-history", "msg-qwen-history", "hello", 3_000L);
    List<ResponseInputItem> originalInput =
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(
                List.of(
                    ConversationTurn.user("Alice: older user text", Instant.ofEpochSecond(1_000L)),
                    ConversationTurn.assistant(
                        "older assistant text", Instant.ofEpochSecond(2_000L))),
                List.of(),
                incoming);
    JsonNode inputNode = JsonValue.from(originalInput).convert(JsonNode.class);
    List<ResponseInputItem> roundTrippedInput =
        JsonValue.fromJsonNode(inputNode).convert(new TypeReference<List<ResponseInputItem>>() {});

    agent.createResponse(roundTrippedInput, incoming, null);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    List<ResponseInputItem> inputItems = paramsCaptor.getValue().input().orElseThrow().asResponse();
    JsonNode requestInputNode = JsonValue.from(inputItems).convert(JsonNode.class);
    assertFalse(
        requestInputNode.toString().contains("\"role\":\"developer\""),
        requestInputNode.toPrettyString());
    assertTrue(requestInputNode.toString().contains("\"role\":\"assistant\""));
    assertTrue(requestInputNode.toString().contains("\"role\":\"system\""));
    assertTrue(requestInputNode.toString().contains(DEVELOPER_PROMPT_MARKER));
  }

  @Test
  void keepsDeveloperMessagesForNonWhitelistedModels() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    ModelAccessService modelAccessService = Mockito.mock(ModelAccessService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());
    when(modelAccessService.resolve(any(IncomingMessage.class)))
        .thenReturn(
            new ModelAccessService.ModelAccess(
                "Alice",
                true,
                ModelAccessService.PREMIUM_MODEL_KEY,
                ModelAccessService.PREMIUM_MODEL_LABEL,
                ModelAccessService.PREMIUM_RESPONSES_MODEL,
                true,
                List.of()));
    BBMessageAgent agent =
        newAgent(openAIClient, new StubBBHttpClientWrapper(), new ModelPicker(modelAccessService));
    IncomingMessage incoming = incomingMessage("iMessage;+;chat-gpt", "msg-gpt", "hello", 1_000L);

    agent.createResponse(
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(List.of(), List.of(), incoming),
        incoming,
        null);

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    List<ResponseInputItem> inputItems = paramsCaptor.getValue().input().orElseThrow().asResponse();
    assertTrue(inputItems.stream().anyMatch(this::isDeveloperEasyInputMessage));
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
  void olderLiveActivityCannotSendWhenAnotherMessageIsLatest() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-live-activity";
    ConversationState state = new ConversationState();
    state.setLatestWorkflowMessageGuid("newer-message");
    agent.getConversations().put(chatGuid, state);
    AgentWorkflowContext oldActivityContext =
        new AgentWorkflowContext(chatGuid + ":old-message", chatGuid, "old-message", Instant.now());
    AgentWorkflowContext latestActivityContext =
        new AgentWorkflowContext(
            chatGuid + ":newer-message", chatGuid, "newer-message", Instant.now());

    assertFalse(agent.canSendResponsesForWorkflowRun(oldActivityContext, null));
    assertFalse(agent.canSendResponsesForWorkflowRun(oldActivityContext, "old-run"));
    assertTrue(agent.canSendResponsesForWorkflowRun(latestActivityContext, null));
    assertTrue(agent.canSendResponsesForWorkflowRun(latestActivityContext, "new-run"));
  }

  @Test
  void cadenceWorkflowIdStaysChatScopedSoNewMessagesCancelOlderRuns() {
    CadenceWorkflowLauncher cadenceWorkflowLauncher = Mockito.mock(CadenceWorkflowLauncher.class);
    WorkflowExecution execution = new WorkflowExecution();
    execution.setRunId("run-1");
    when(cadenceWorkflowLauncher.startWorkflow(any(CadenceMessageWorkflowRequest.class)))
        .thenReturn(execution);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent =
        new BBMessageAgent(
            null,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            null,
            Mockito.mock(GiphyClient.class),
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            cadenceWorkflowLauncher,
            null,
            null,
            null,
            new ModelPicker());
    String chatGuid = "iMessage;+;chat-cadence-coalesce";

    agent.handleIncomingMessage(incomingMessage(chatGuid, "msg-cadence-1", "first", 1_000L));

    ArgumentCaptor<CadenceMessageWorkflowRequest> requestCaptor =
        ArgumentCaptor.forClass(CadenceMessageWorkflowRequest.class);
    verify(cadenceWorkflowLauncher).startWorkflow(requestCaptor.capture());
    assertEquals(chatGuid, requestCaptor.getValue().workflowContext().workflowId());
    assertEquals("msg-cadence-1", requestCaptor.getValue().workflowContext().messageGuid());
  }

  @Test
  void latestWorkflowInputIncludesPendingMessagesSinceLastAssistantTurn() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    ConversationState state = new ConversationState();
    IncomingMessage first =
        incomingMessage("iMessage;+;chat-pending", "msg-pending-1", "first request", 1_000L);
    IncomingMessage second =
        incomingMessage("iMessage;+;chat-pending", "msg-pending-2", "second request", 2_000L);
    state.recordPendingIncomingTurn(first);
    state.recordPendingIncomingTurn(second);

    List<ResponseInputItem> input =
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(state.history(), state.pendingIncomingTurns(), second);
    List<String> texts = input.stream().map(this::extractText).toList();

    assertTrue(texts.stream().anyMatch(text -> text.contains("Alice: first request")));
    assertEquals(1L, texts.stream().filter(text -> text.contains("second request")).count());
  }

  @Test
  void assistantTurnDrainsPendingMessagesInOrder() {
    BBMessageAgent agent =
        newAgent(Mockito.mock(OpenAIClient.class), new StubBBHttpClientWrapper());
    String chatGuid = "iMessage;+;chat-pending-record";
    ConversationState state = new ConversationState();
    agent.getConversations().put(chatGuid, state);
    IncomingMessage first = incomingMessage(chatGuid, "msg-pending-a", "first request", 1_000L);
    IncomingMessage second = incomingMessage(chatGuid, "msg-pending-b", "second request", 2_000L);
    state.recordPendingIncomingTurn(first);
    state.recordPendingIncomingTurn(second);

    agent.recordAssistantTurnForCurrentMessage(second, "Handled both.", null);

    List<ConversationTurn> history = state.history();
    assertEquals(3, history.size());
    assertEquals("Alice: first request", history.get(0).content());
    assertEquals("Alice: second request", history.get(1).content());
    assertEquals("Handled both.", history.get(2).content());
    assertTrue(state.pendingIncomingTurns().isEmpty());
  }

  @Test
  void truncatesLargeToolOutputBeforeReturningItToModel() {
    String output = "a".repeat(30_000);

    String truncated = BBMessageAgent.truncateToolOutputForModel(output, "kubernetes_get_pod_logs");

    assertTrue(truncated.length() < output.length());
    assertTrue(truncated.contains("kubernetes_get_pod_logs output truncated for model context"));
    assertTrue(truncated.startsWith("a".repeat(100)));
    assertTrue(truncated.endsWith("a".repeat(100)));
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
    CadenceWorkflowLauncher cadenceWorkflowLauncher = Mockito.mock(CadenceWorkflowLauncher.class);
    String chatGuid = "iMessage;+;chat-duplicate";

    when(openAIClient.responses()).thenReturn(responseService);
    WorkflowExecution execution = new WorkflowExecution();
    execution.setRunId("run-duplicate");
    when(cadenceWorkflowLauncher.startWorkflow(any(CadenceMessageWorkflowRequest.class)))
        .thenReturn(execution);
    BBMessageAgent agent = newAgent(openAIClient, bbHttpClientWrapper, cadenceWorkflowLauncher);
    IncomingMessage incoming =
        incomingMessage(chatGuid, "msg-duplicate", "please only process once", 1_000L);

    agent.handleIncomingMessage(incoming);
    agent.handleIncomingMessage(incoming);

    verify(cadenceWorkflowLauncher, times(1))
        .startWorkflow(any(CadenceMessageWorkflowRequest.class));
    verify(responseService, never()).create(any(ResponseCreateParams.class));
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
            null,
            giphyClient,
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            Mockito.mock(CadenceWorkflowLauncher.class),
            null,
            null,
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

    agent.createResponse(
        promptBuilder(new StubBBHttpClientWrapper())
            .buildConversationInput(List.of(), List.of(), incoming),
        incoming,
        new AgentWorkflowContext(
            incoming.chatGuid(), incoming.chatGuid(), incoming.messageGuid(), Instant.now()));

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    assertTrue(paramsCaptor.getValue().toString().contains(RenameConversationAgentTool.TOOL_NAME));
  }

  @Test
  void doesNotInjectRetiredTools() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    var responseService = Mockito.mock(com.openai.services.blocking.ResponseService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();

    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(ResponseCreateParams.class)))
        .thenReturn(responseWithNoToolCalls());

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            null,
            Mockito.mock(GiphyClient.class),
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            Mockito.mock(CadenceWorkflowLauncher.class),
            null,
            null,
            null,
            new ModelPicker());

    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-tools", "msg-tools", "what tools are available?", 1_000L);
    agent.createResponse(
        promptBuilder(bbHttpClientWrapper).buildConversationInput(List.of(), List.of(), incoming),
        incoming,
        new AgentWorkflowContext(
            incoming.chatGuid(), incoming.chatGuid(), incoming.messageGuid(), Instant.now()));

    ArgumentCaptor<ResponseCreateParams> paramsCaptor =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responseService).create(paramsCaptor.capture());
    String request = paramsCaptor.getValue().toString();
    assertFalse(request.contains("retired_auth"));
    assertFalse(request.contains("start_retired_async_task"));
    assertFalse(request.contains("retired__"));
  }

  @Test
  void doesNotResolveUnknownToolCalls() {
    OpenAIClient openAIClient = Mockito.mock(OpenAIClient.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();

    BBMessageAgent agent =
        new BBMessageAgent(
            openAIClient,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            null,
            Mockito.mock(GiphyClient.class),
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            Mockito.mock(CadenceWorkflowLauncher.class),
            null,
            null,
            null,
            new ModelPicker());

    IncomingMessage incoming =
        incomingMessage("iMessage;+;chat-tool", "msg-tool", "run a retired tool", 1_000L);
    ResponseInputItem output =
        agent.runToolActivity(
            ResponseFunctionToolCall.builder()
                .name("retired__list_templates_abc123")
                .arguments("{}")
                .callId("call-retired")
                .build(),
            incoming,
            new AgentWorkflowContext(
                incoming.chatGuid(), incoming.chatGuid(), incoming.messageGuid(), Instant.now()));

    assertTrue(output.toString().contains("Unknown tool: retired__list_templates_abc123"));
  }

  @Test
  void runToolActivityRecordsSuccessAndFailureMetrics() {
    AgentMetricsService metricsService = Mockito.mock(AgentMetricsService.class);
    StubBBHttpClientWrapper bbHttpClientWrapper = new StubBBHttpClientWrapper();
    BBMessageAgent agent =
        new BBMessageAgent(
            null,
            bbHttpClientWrapper,
            Mockito.mock(Mem0Client.class),
            Mockito.mock(GcalClient.class),
            null,
            Mockito.mock(GiphyClient.class),
            profileService(),
            attachmentInputBuilder(bbHttpClientWrapper),
            null,
            null,
            Mockito.mock(CadenceWorkflowLauncher.class),
            metricsService,
            null,
            null,
            new ModelPicker());
    IncomingMessage message =
        incomingMessage("iMessage;+;chat-tool-metrics", "msg-tool-metrics", "remember my name", 1L);

    agent.runToolActivity(
        ResponseFunctionToolCall.builder()
            .name(AssistantNameAgentTool.TOOL_NAME)
            .arguments("{\"action\":\"store\",\"name\":\"Alice\"}")
            .callId("call-success")
            .build(),
        message,
        null);
    agent.runToolActivity(
        ResponseFunctionToolCall.builder()
            .name("missing_tool")
            .arguments("{}")
            .callId("call-failure")
            .build(),
        message,
        null);

    ArgumentCaptor<AgentToolMetricEvent> metricsCaptor =
        ArgumentCaptor.forClass(AgentToolMetricEvent.class);
    verify(metricsService, times(2)).recordToolCall(metricsCaptor.capture());
    List<AgentToolMetricEvent> events = metricsCaptor.getAllValues();
    assertTrue(events.get(0).success());
    assertEquals(AssistantNameAgentTool.TOOL_NAME, events.get(0).toolName());
    assertEquals("assistant", events.get(0).toolCategory());
    assertEquals(null, events.get(0).failureType());
    assertFalse(events.get(1).success());
    assertEquals("missing_tool", events.get(1).toolName());
    assertEquals("unknown_tool", events.get(1).failureType());
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

  private boolean isDeveloperEasyInputMessage(ResponseInputItem item) {
    return item.isEasyInputMessage()
        && EasyInputMessage.Role.DEVELOPER.equals(item.asEasyInputMessage().role());
  }

  private boolean isDeveloperInputMessage(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      return EasyInputMessage.Role.DEVELOPER.equals(item.asEasyInputMessage().role());
    }
    if (item.isMessage()) {
      return ResponseInputItem.Message.Role.DEVELOPER.equals(item.asMessage().role());
    }
    return false;
  }

  private boolean isSystemInputMessage(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      return EasyInputMessage.Role.SYSTEM.equals(item.asEasyInputMessage().role());
    }
    if (item.isMessage()) {
      return ResponseInputItem.Message.Role.SYSTEM.equals(item.asMessage().role());
    }
    return false;
  }

  private String extractText(ResponseInputItem item) {
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

  private static BBMessageAgent newAgent(
      OpenAIClient openAIClient, BBHttpClientWrapper bbHttpClientWrapper) {
    return newAgent(openAIClient, bbHttpClientWrapper, new ModelPicker());
  }

  private static AgentPromptBuilder promptBuilder(BBHttpClientWrapper bbHttpClientWrapper) {
    return promptBuilder(bbHttpClientWrapper, null, ReverseLocationLookup.noop());
  }

  private static AgentProfileService profileService() {
    return profileService(null);
  }

  private static AgentProfileService profileService(AgentAccountResolver accountResolver) {
    return new AgentProfileService(new InMemoryAgentSettingsStore(), accountResolver);
  }

  private static AgentAttachmentInputBuilder attachmentInputBuilder(
      BBHttpClientWrapper bbHttpClientWrapper) {
    return new AgentAttachmentInputBuilder(bbHttpClientWrapper);
  }

  private static AgentPromptBuilder promptBuilder(
      BBHttpClientWrapper bbHttpClientWrapper,
      WebsiteAccountService websiteAccountService,
      ReverseLocationLookup reverseLocationLookup) {
    return new AgentPromptBuilder(
        bbHttpClientWrapper,
        reverseLocationLookup,
        profileService(),
        attachmentInputBuilder(bbHttpClientWrapper),
        websiteAccountService,
        null);
  }

  private static BBMessageAgent newAgent(
      OpenAIClient openAIClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    return new BBMessageAgent(
        openAIClient,
        bbHttpClientWrapper,
        Mockito.mock(Mem0Client.class),
        Mockito.mock(GcalClient.class),
        null,
        Mockito.mock(GiphyClient.class),
        profileService(),
        attachmentInputBuilder(bbHttpClientWrapper),
        null,
        null,
        cadenceWorkflowLauncher,
        null,
        null,
        null,
        new ModelPicker());
  }

  private static BBMessageAgent newAgent(
      OpenAIClient openAIClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      AgentAccountResolver accountResolver) {
    return new BBMessageAgent(
        openAIClient,
        bbHttpClientWrapper,
        Mockito.mock(Mem0Client.class),
        Mockito.mock(GcalClient.class),
        null,
        Mockito.mock(GiphyClient.class),
        profileService(accountResolver),
        attachmentInputBuilder(bbHttpClientWrapper),
        null,
        null,
        Mockito.mock(CadenceWorkflowLauncher.class),
        null,
        null,
        null,
        new ModelPicker());
  }

  private static BBMessageAgent newAgent(
      OpenAIClient openAIClient, BBHttpClientWrapper bbHttpClientWrapper, ModelPicker modelPicker) {
    return new BBMessageAgent(
        openAIClient,
        bbHttpClientWrapper,
        Mockito.mock(Mem0Client.class),
        Mockito.mock(GcalClient.class),
        null,
        Mockito.mock(GiphyClient.class),
        profileService(),
        attachmentInputBuilder(bbHttpClientWrapper),
        null,
        null,
        Mockito.mock(CadenceWorkflowLauncher.class),
        null,
        null,
        null,
        modelPicker);
  }

  private static class StubReverseLocationLookup implements ReverseLocationLookup {
    private Optional<ReverseLocationLookupResult> result = Optional.empty();
    private int reverseLookupCalls;
    private double lastLatitude;
    private double lastLongitude;

    void setResult(String displayName) {
      this.result = Optional.of(new ReverseLocationLookupResult(displayName, Map.of()));
    }

    @Override
    public Optional<ReverseLocationLookupResult> reverseLookup(double latitude, double longitude) {
      reverseLookupCalls++;
      lastLatitude = latitude;
      lastLongitude = longitude;
      return result;
    }
  }

  private static class StubBBHttpClientWrapper extends BBHttpClientWrapper {
    private final Map<String, List<ApiV1ChatChatGuidMessageGet200ResponseDataInner>> messages =
        new ConcurrentHashMap<>();
    private int renameCalls;
    private String renamedChatGuid;
    private String renamedDisplayName;
    private boolean renameResult = true;
    private final Map<String, FindMyFriendLocation> findMyLocations = new ConcurrentHashMap<>();
    private int findMyLookupCalls;
    private String lastFindMyUserId;
    private List<String> lastFindMyUserIds = List.of();
    private int readPollCalls;
    private final List<String> sentTexts = new ArrayList<>();

    StubBBHttpClientWrapper() {
      super("pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class));
    }

    void setMessages(
        String chatGuid, List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages) {
      this.messages.put(chatGuid, messages);
    }

    void setFindMyLocation(String userId, FindMyFriendLocation location) {
      this.findMyLocations.put(userId, location);
    }

    @Override
    public List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> getMessagesInChat(
        String chatGuid) {
      return messages.getOrDefault(chatGuid, List.of());
    }

    @Override
    public FindMyFriendLocation getFindMyLocation(String userId) {
      return getFindMyLocation(userId == null ? List.of() : List.of(userId));
    }

    @Override
    public FindMyFriendLocation getFindMyLocation(Collection<String> userIds) {
      findMyLookupCalls++;
      lastFindMyUserIds = userIds == null ? List.of() : userIds.stream().toList();
      lastFindMyUserId = lastFindMyUserIds.isEmpty() ? null : lastFindMyUserIds.getFirst();
      return lastFindMyUserIds.stream()
          .map(findMyLocations::get)
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(null);
    }

    @Override
    public JsonNode readPollJson(String messageGuid) {
      readPollCalls++;
      return getObjectMapper()
          .createObjectNode()
          .put("messageGuid", messageGuid)
          .put("title", "Lunch?");
    }

    @Override
    public boolean sendTextDirect(ApiV1MessageTextPostRequest request) {
      sentTexts.add(request.getMessage());
      return true;
    }

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

  private static IncomingMessage pollIncomingMessage(
      String chatGuid, String messageGuid, String associatedMessageGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        messageGuid,
        null,
        null,
        false,
        "iMessage",
        "Alice",
        false,
        Instant.ofEpochSecond(1_000L),
        List.of(),
        "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls",
        associatedMessageGuid,
        null,
        false);
  }

  private static AgentAccountEntity account(String accountId, Instant termsAcceptedAt) {
    Instant now = Instant.now();
    AgentAccountEntity account = new AgentAccountEntity(accountId, now, now);
    account.setTermsAcceptedAt(termsAcceptedAt);
    return account;
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

  private static FindMyFriendLocation findMyLocation(
      String handle,
      List<Double> coordinates,
      String shortAddress,
      String longAddress,
      long lastUpdated) {
    return FindMyFriendLocation.builder()
        .handle(handle)
        .coordinates(coordinates)
        .shortAddress(shortAddress)
        .longAddress(longAddress)
        .subtitle(shortAddress)
        .title(handle)
        .lastUpdated(lastUpdated)
        .isLocatingInProgress(false)
        .status(FindMyFriendLocation.StatusEnum.SHALLOW)
        .build();
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
        .toolChoice(ToolChoiceOptions.AUTO)
        .tools(List.of())
        .topP(1.0)
        .build();
  }

  private static class InMemoryAgentSettingsStore implements AgentSettingsStore {
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
