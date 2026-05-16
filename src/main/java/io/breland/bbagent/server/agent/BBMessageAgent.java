package io.breland.bbagent.server.agent;

import static io.breland.bbagent.server.agent.AgentResponseHelper.parseReactionText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.*;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInfo;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.server.admin.AdminStatsService;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.GeneratedImage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.location.ReverseLocationLookup;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.assistant.AssistantResponsivenessAgentTool;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.RenameConversationAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SearchConvoHistoryAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SetGroupIconAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderAsyncTaskStartStore;
import io.breland.bbagent.server.agent.tools.coder.CoderAuthAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.coder.StartCoderAsyncTaskAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.*;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesPodLogsAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesReadOnlyAgentTool;
import io.breland.bbagent.server.agent.tools.memory.*;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventDeleteTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventListTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.tools.website.GetWebsiteAccountLinkStatusAgentTool;
import io.breland.bbagent.server.agent.tools.website.LinkWebsiteAccountAgentTool;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BBMessageAgent {

  public static final int MAX_HISTORY = 50;
  public static final String NO_RESPONSE_TEXT = "NO_RESPONSE";
  private static final int MAX_TOOL_LOOPS = 50;
  private static final int MAX_CONSECUTIVE_BLOCKED_TOOL_LOOPS = 2;
  private static final int MAX_IMAGE_ATTACHMENTS = 4;
  private static final int MAX_FILE_ATTACHMENTS = 4;
  private static final int MAX_GENERATED_IMAGES = 1;
  public static final String IMESSAGE_SERVICE = "iMessage";
  private static final String IMESSAGE_FORMATTING_INSTRUCTION =
      "iMessage supports basic text formatting, specifically bold, italic, underline, and"
          + " strikethrough. Bold is delimited with **, underline with __, strikethrough with ~~, and"
          + " italic with *. Constrain output to those formatting markers, plain text, and emojis."
          + " Do not use unsupported markdown such as backticks, headings, tables, or lists. ";

  public enum AssistantResponsiveness {
    DEFAULT,
    LESS_RESPONSIVE,
    MORE_RESPONSIVE,
    SILENT
  }

  private static final Set<String> GROUP_ONLY_TOOLS =
      Set.of(RenameConversationAgentTool.TOOL_NAME, SetGroupIconAgentTool.TOOL_NAME);
  private static final Set<String> BLUEBUBBLES_ONLY_TOOLS =
      Set.of(
          SearchConvoHistoryAgentTool.TOOL_NAME,
          CurrentConversationInfoAgentTool.TOOL_NAME,
          RenameConversationAgentTool.TOOL_NAME,
          SetGroupIconAgentTool.TOOL_NAME,
          SendGiphyAgentTool.TOOL_NAME,
          GetThreadContextAgentTool.TOOL_NAME);
  private static final Set<String> HIDDEN_CODER_MCP_TOOL_NAMES =
      Set.of(StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL);
  public static final Set<String> SUPPORTED_REACTIONS =
      Set.of(
          "love",
          "like",
          "dislike",
          "laugh",
          "emphasize",
          "question",
          "-love",
          "-like",
          "-dislike",
          "-laugh",
          "-emphasize",
          "-question");
  private static final String KUBERNETES_TOOL_ALLOWED_SENDER = "+18033861737";

  private static final List<String> REACTION_PREFIXES =
      List.of(
          "reacted ", "loved ", "liked ", "disliked ", "questioned ", "emphasized ", "laughed at ");

  @Getter private final ObjectMapper objectMapper;
  @Getter private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  private final AgentSettingsStore agentSettingsStore;
  private final AgentWorkflowProperties workflowProperties;
  private final MessageTransportRegistry transportRegistry;
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;
  private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

  private OpenAIClient openAIClient;
  private final Supplier<OpenAIClient> openAiSupplier =
      () -> {
        if (openAIClient == null) {
          openAIClient =
              OpenAIOkHttpClient.fromEnv().withOptions(b -> b.timeout(Duration.ofSeconds(120)));
        }
        return openAIClient;
      };

  private BBHttpClientWrapper bbHttpClientWrapper;
  private Mem0Client mem0Client;
  private GcalClient gcalClient;
  private CoderMcpClient coderMcpClient;
  private WorkflowCallbackService workflowCallbackService;
  private CoderAsyncTaskStartStore coderAsyncTaskStartStore;
  private WebsiteAccountService websiteAccountService;
  private GiphyClient giphyClient;
  private ModelPicker modelPicker;
  private AdminStatsService adminStatsService;
  private final ReverseLocationLookup reverseLocationLookup;
  private final AccountIdentityAliasService accountIdentityAliasService;

  @Autowired
  public BBMessageAgent(
      @Nullable OpenAIClient openAiClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      CoderMcpClient coderMcpClient,
      WorkflowCallbackService workflowCallbackService,
      CoderAsyncTaskStartStore coderAsyncTaskStartStore,
      WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      ReverseLocationLookup reverseLocationLookup,
      AgentSettingsStore agentSettingsStore,
      AgentWorkflowProperties workflowProperties,
      MessageTransportRegistry transportRegistry,
      @Nullable ObjectMapper objectMapper,
      @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher,
      @Nullable AccountIdentityAliasService accountIdentityAliasService,
      @Nullable AdminStatsService adminStatsService,
      ModelPicker modelPicker) {
    if (openAiClient != null) {
      this.openAIClient = openAiClient;
    }
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mem0Client = mem0Client;
    this.gcalClient = gcalClient;
    this.coderMcpClient = coderMcpClient;
    this.workflowCallbackService = workflowCallbackService;
    this.coderAsyncTaskStartStore = coderAsyncTaskStartStore;
    this.websiteAccountService = websiteAccountService;
    this.giphyClient = giphyClient;
    this.reverseLocationLookup =
        reverseLocationLookup == null ? ReverseLocationLookup.noop() : reverseLocationLookup;
    this.agentSettingsStore = agentSettingsStore;
    this.workflowProperties = workflowProperties;
    this.transportRegistry =
        transportRegistry != null
            ? transportRegistry
            : MessageTransportRegistry.blueBubblesOnly(bbHttpClientWrapper);
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    this.accountIdentityAliasService = accountIdentityAliasService;
    this.adminStatsService = adminStatsService;
    this.modelPicker = modelPicker;
    registerBuiltInTools();
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    MessageTransport transport = transportFor(message);
    ConversationState stateToHydrate = new ConversationState();
    log.info("Hydrating conversation state for chat {} via {}", chatId, transport.displayName());
    try {
      stateToHydrate = transport.hydrateConversationState(chatId, message);
      log.info(
          "Hydrated conversation state for chat {} got {} messages from history latestIncomingTs={} lastIncomingGuid={}",
          chatId,
          stateToHydrate.history().size(),
          stateToHydrate.getLatestProcessedMessageTimestamp(),
          stateToHydrate.getLastProcessedMessageGuid());
    } catch (Exception e) {
      log.warn("Failed to hydrate conversation state for chat {}", chatId, e);
    }
    return stateToHydrate;
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    if (!shouldProcess(message)) {
      log.debug("Dropping message {}", message);
      return;
    }
    log.info("Processing Message {}", message);
    recordAccountAliases(message);
    ConversationState state =
        conversations.computeIfAbsent(
            message.chatGuid(), key -> this.computeConversationState(key, message));

    synchronized (state) {
      if (state.hasSeenIncomingMessage(message)) {
        log.info(
            "Dropping already-seen incoming message chat={} guid={} fingerprint={} ts={} lastSeenGuid={} latestSeenTs={}",
            message.chatGuid(),
            message.messageGuid(),
            message.computeMessageFingerprint(),
            message.timestamp(),
            state.getLastProcessedMessageGuid(),
            state.getLatestProcessedMessageTimestamp());
        return;
      }
      if (state.isStaleIncomingMessage(message)) {
        log.info(
            "Dropping stale incoming message chat={} guid={} ts={} latestSeenTs={} lastSeenGuid={}",
            message.chatGuid(),
            message.messageGuid(),
            message.timestamp(),
            state.getLatestProcessedMessageTimestamp(),
            state.getLastProcessedMessageGuid());
        return;
      }
      state.markIncomingMessageSeen(message);
    }
    recordAcceptedMessageMetric(message);
    if (workflowProperties.useCadenceWorkflow()) {
      if (cadenceWorkflowLauncher == null) {
        log.error(
            "Cadence workflow launcher is not configured - but we should use cadence workflow; incorrectly dropping message {}",
            message);
        return;
      }
    }
    String workflowId = resolveWorkflowId(message);
    AgentWorkflowContext workflowContext =
        new AgentWorkflowContext(
            workflowId, message.chatGuid(), message.messageGuid(), Instant.now());
    if (workflowProperties.useCadenceWorkflow()) {
      log.info("Responding via cadence workflow");
      CadenceMessageWorkflowRequest request =
          new CadenceMessageWorkflowRequest(workflowContext, message, null);
      WorkflowExecution execution = cadenceWorkflowLauncher.startWorkflow(request);
      state.setLatestWorkflowRunId(execution.getRunId());
      return;
    }
    log.info("Responding via inline workflow");
    runMessageWorkflow(state, message, workflowContext);
  }

  private void recordAcceptedMessageMetric(IncomingMessage message) {
    if (adminStatsService == null) {
      return;
    }
    try {
      adminStatsService.recordAcceptedMessage(message, workflowProperties.getMode());
    } catch (RuntimeException e) {
      log.warn("Failed to record admin message metric for {}", message, e);
    }
  }

  private boolean shouldProcess(IncomingMessage message) {
    if (message == null) {
      return false;
    }
    if (Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    if (message.chatGuid() == null || message.chatGuid().isBlank()) {
      return false;
    }
    if (message.isBlueBubblesTransport()
        && message.service() != null
        && !IMESSAGE_SERVICE.equalsIgnoreCase(message.service())) {
      return false;
    }
    if (isReactionMessage(message.text())) {
      return false;
    }
    if ((message.text() == null || message.text().isBlank())
        && (message.attachments() == null || message.attachments().isEmpty())) {
      // assume it's a group name or photo edited
      // there's no text to process and no attachments so must be that?
      return false;
    }
    AssistantResponsiveness responsiveness = getAssistantResponsiveness(message.chatGuid());
    if (responsiveness == AssistantResponsiveness.SILENT) {
      return isSilentInvocation(message.text());
    }
    return true;
  }

  private String resolveWorkflowId(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    // in cadecne - we will terminate an existing workflow with the same id
    // in both groups, and regular texts - we use the group chat as the main workflow id
    // such that new messages will cancel current ones.
    // this is useful if we have an active chat- and we want the response to kind of happen "all at
    // once"
    // instead of serially processing
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      return message.chatGuid();
    }
    log.warn("Message did not have a chat guid - this is unexpected");
    return UUID.randomUUID().toString();
  }

  private void runMessageWorkflow(
      ConversationState state, IncomingMessage message, AgentWorkflowContext workflowContext) {
    Response response = null;
    try {
      response = runAssistant(state, message, workflowContext);
    } catch (RuntimeException e) {
      log.warn("Workflow failed for {}", message, e);
    } finally {
      synchronized (state) {
        if (response != null) {
          state.recordIncomingTurnIfAbsent(message);
        }
        state.markIncomingMessageSeen(message);
        updateThreadContext(state, message);
      }
    }
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    if (workflowContext == null) {
      return true;
    }
    String currentRunId = null;
    try {
      WorkflowInfo info = Workflow.getWorkflowInfo();
      if (info != null && info.getRunId() != null) {
        currentRunId = info.getRunId();
      }
    } catch (Error e) {
      if (e.getMessage() != null && e.getMessage().contains("non workflow")) {
        currentRunId = null;
      } else {
        throw e;
      }
    }
    return canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    if (workflowContext == null) {
      return true;
    }
    if (ScheduledEventTool.isScheduledWorkflowId(workflowContext.workflowId())) {
      return true;
    }
    if (WorkflowCallbackService.isCallbackWorkflowId(workflowContext.workflowId())) {
      return true;
    }
    if (workflowContext.chatGuid() == null || workflowContext.chatGuid().isBlank()) {
      return true;
    }
    if (currentRunId == null || currentRunId.isBlank()) {
      return true;
    }
    ConversationState state = conversations.get(workflowContext.chatGuid());
    if (state == null) {
      return true;
    }
    synchronized (state) {
      String latestWorkflowRunId = state.getLatestWorkflowRunId();

      // can be null until we persist state in a real db.
      return latestWorkflowRunId == null || latestWorkflowRunId.equals(currentRunId);
    }
  }

  private boolean isSilentInvocation(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    return trimmed.regionMatches(true, 0, "Chat", 0, 4);
  }

  public static boolean isReactionMessage(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    if (trimmed.isBlank()) {
      return false;
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT);
    for (String prefix : REACTION_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  Response runAssistant(
      ConversationState state, IncomingMessage message, AgentWorkflowContext workflowContext) {
    List<ConversationTurn> historySnapshot;
    Instant latestSeenTimestamp;
    String lastSeenGuid;
    synchronized (state) {
      historySnapshot = state.history();
      latestSeenTimestamp = state.getLatestProcessedMessageTimestamp();
      lastSeenGuid = state.getLastProcessedMessageGuid();
    }
    log.info(
        "Building LLM input chat={} messageGuid={} messageTs={} historyTurns={} oldestHistoryTs={} newestHistoryTs={} latestSeenTs={} lastSeenGuid={}",
        message.chatGuid(),
        message.messageGuid(),
        message.timestamp(),
        historySnapshot.size(),
        oldestTurnTimestamp(historySnapshot),
        newestTurnTimestamp(historySnapshot),
        latestSeenTimestamp,
        lastSeenGuid);
    List<ResponseInputItem> inputItems = buildConversationInput(historySnapshot, message);
    log.trace("Getting response for {}", inputItems.toString());
    Response response = createResponse(inputItems, message, workflowContext);
    if (response == null) {
      log.warn("Got a null response for {}", message.text());
      return null;
    }
    log.debug(response.toString());
    boolean sentTextByTool = false;
    boolean sentReactionByTool = false;
    int loops = 0;
    int blockedLoops = 0;
    AgentToolLoopGuard toolLoopGuard = AgentToolLoopGuard.standard();
    while (loops < MAX_TOOL_LOOPS) {
      List<ResponseFunctionToolCall> toolCalls = AgentResponseHelper.extractFunctionCalls(response);
      log.debug(toolCalls.toString());
      if (toolCalls.isEmpty()) {
        break;
      }
      if (toolCalls.stream().anyMatch(call -> SendTextAgentTool.TOOL_NAME.equals(call.name()))) {
        sentTextByTool = true;
      }
      if (toolCalls.stream()
          .anyMatch(call -> SendReactionAgentTool.TOOL_NAME.equals(call.name()))) {
        sentReactionByTool = true;
      }
      List<ResponseInputItem> toolContinuation = new ArrayList<>(inputItems);
      log.debug(toolContinuation.toString());
      toolContinuation.addAll(AgentResponseHelper.extractToolContextItems(response, toolCalls));
      boolean blockedAnyToolCall = false;
      int executedToolCalls = 0;
      for (ResponseFunctionToolCall toolCall : toolCalls) {
        if (toolLoopGuard.shouldBlock(toolCall.name(), toolCall.arguments())) {
          blockedAnyToolCall = true;
          toolContinuation.add(
              AgentResponseHelper.blockedToolCallOutput(toolCall.callId(), toolCall.name()));
          continue;
        }
        toolContinuation.add(runToolActivity(toolCall, message, workflowContext));
        executedToolCalls++;
      }
      response = createResponse(toolContinuation, message, workflowContext);
      inputItems = toolContinuation;
      boolean onlyBlockedToolCalls = blockedAnyToolCall && executedToolCalls == 0;
      blockedLoops = onlyBlockedToolCalls ? blockedLoops + 1 : 0;
      if (blockedLoops >= MAX_CONSECUTIVE_BLOCKED_TOOL_LOOPS) {
        log.warn(
            "Stopping tool loop after {} consecutive blocked iterations for chat={} messageGuid={}",
            blockedLoops,
            message.chatGuid(),
            message.messageGuid());
        break;
      }
      loops++;
    }
    String assistantText =
        AgentResponseHelper.normalizeAssistantText(
            objectMapper, AgentResponseHelper.extractResponseText(response));
    List<GeneratedImage> generatedImages = extractGeneratedImages(response);
    MessageTransport transport = transportFor(message);
    log.info("Extracted " + generatedImages.size() + " images from assistant response");
    boolean sentImageByMultipart = false;
    if (!generatedImages.isEmpty() && transport.supportsGeneratedImages()) {
      log.info("Found {} image for multipart requests", generatedImages.size());
      String caption = assistantText;
      if (caption != null) {
        String trimmed = caption.trim();
        if (trimmed.isBlank()
            || NO_RESPONSE_TEXT.equalsIgnoreCase(trimmed)
            || parseReactionText(trimmed).isPresent()) {
          caption = null;
        }
      }
      List<BBHttpClientWrapper.AttachmentData> attachments = new ArrayList<>();
      for (GeneratedImage image : generatedImages) {
        attachments.add(new BBHttpClientWrapper.AttachmentData(image.filename(), image.bytes()));
      }
      if (canSendResponses(workflowContext)) {
        sentImageByMultipart =
            transport.sendMultipartMessage(message.chatGuid(), caption, attachments);
        if (sentImageByMultipart && caption != null && !caption.isBlank()) {
          sentTextByTool = true;
        }
      } else {
        log.info("Skipping multipart image send for outdated workflow {}", workflowContext);
      }
    } else if (!generatedImages.isEmpty()) {
      log.info("Skipping generated image send for unsupported transport {}", transport.id());
    }
    Optional<String> parsedReaction = parseReactionText(assistantText);
    if (parsedReaction.isPresent() && transport.supportsReactions()) {
      String reaction = parsedReaction.get();
      if (sentReactionByTool) {
        log.debug("Skipping reaction text output since reaction tool already ran");
      } else if (canSendResponses(workflowContext) && transport.sendReaction(message, reaction)) {
        recordAssistantTurnForCurrentMessage(
            message, "[reaction: " + reaction + "]", workflowContext);
      } else {
        log.warn("Unable to send reaction for assistant text: {}", assistantText);
      }
      return response;
    }
    if (!assistantText.isBlank() && !NO_RESPONSE_TEXT.equalsIgnoreCase(assistantText.trim())) {
      log.info("Assistant reply text: {}", assistantText);
      if (!sentTextByTool && !sentImageByMultipart && canSendResponses(workflowContext)) {
        sendThreadAwareText(message, assistantText.trim());
      } else if (!canSendResponses(workflowContext)) {
        log.info("Skipping direct response send for outdated workflow {}", workflowContext);
      }
      if (canSendResponses(workflowContext)) {
        recordAssistantTurnForCurrentMessage(message, assistantText.trim(), workflowContext);
      }
    } else {
      if (sentImageByMultipart) {
        if (canSendResponses(workflowContext)) {
          recordAssistantTurnForCurrentMessage(message, "[image]", workflowContext);
        }
      } else {
        log.info("No assistant reply generated");
      }
    }
    return response;
  }

  public void recordAssistantTurnForCurrentMessage(
      IncomingMessage message, String content, AgentWorkflowContext workflowContext) {
    if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
      return;
    }
    if (content == null || content.isBlank()) {
      return;
    }
    if (!canSendResponses(workflowContext)) {
      return;
    }
    ConversationState state = conversations.get(message.chatGuid());
    if (state == null) {
      return;
    }
    synchronized (state) {
      state.recordIncomingTurnIfAbsent(message);
      state.addTurn(ConversationTurn.assistant(content, Instant.now()));
    }
  }

  private Instant oldestTurnTimestamp(List<ConversationTurn> history) {
    if (history == null || history.isEmpty()) {
      return null;
    }
    return history.stream()
        .map(ConversationTurn::timestamp)
        .filter(Objects::nonNull)
        .min(Instant::compareTo)
        .orElse(null);
  }

  private Instant newestTurnTimestamp(List<ConversationTurn> history) {
    if (history == null || history.isEmpty()) {
      return null;
    }
    return history.stream()
        .map(ConversationTurn::timestamp)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .orElse(null);
  }

  public void sendThreadAwareText(IncomingMessage message, String text) {
    if (message == null || text == null || text.isBlank()) {
      return;
    }
    MessageTransport transport = transportFor(message);
    String replyTarget = transport.supportsThreadReplies() ? resolveThreadRootGuid(message) : null;
    transport.sendText(message, new OutgoingTextMessage(text, replyTarget, null, null));
  }

  BBHttpClientWrapper getBbHttpClientWrapper() {
    return bbHttpClientWrapper;
  }

  public boolean sendTextFromTool(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    return transportFor(message).sendText(message, outgoingMessage);
  }

  public boolean sendReactionFromTool(IncomingMessage message, String reaction) {
    if (message == null || reaction == null || reaction.isBlank()) {
      return false;
    }
    MessageTransport transport = transportFor(message);
    if (!transport.supportsReactions()) {
      return false;
    }
    return transport.sendReaction(message, reaction);
  }

  public boolean sendReactionFromTool(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex) {
    if (message == null || reaction == null || reaction.isBlank()) {
      return false;
    }
    MessageTransport transport = transportFor(message);
    if (!transport.supportsReactions()) {
      return false;
    }
    return transport.sendReaction(
        message, conversationId, selectedMessageGuid, reaction, partIndex);
  }

  public MessageTransport transportFor(IncomingMessage message) {
    return transportRegistry.resolve(message);
  }

  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> requestInputItems =
        modelPicker.shouldSquashDeveloperMessagesIntoSystem(message)
            ? ResponseInputMessages.squashDeveloperMessagesIntoSystem(inputItems)
            : inputItems;
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder().inputOfResponse(requestInputItems);
    modelPicker.applyResponsesModelParams(params, message, workflowContext);
    for (AgentTool tool : availableTools(message)) {
      if (shouldIncludeTool(tool, message)) {
        params.addTool(Tool.ofFunction(tool.asFunctionTool()));
      }
    }
    try {
      ResponseCreateParams finalRequest = params.build();
      log.trace("Final message: {}", finalRequest.toString());
      return openAiSupplier.get().responses().create(finalRequest);
    } catch (RuntimeException e) {
      log.warn("OpenAI response failed", e);
      return null;
    }
  }

  public List<ResponseInputItem> executeToolCalls(
      List<ResponseFunctionToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> outputs = new ArrayList<>();
    for (ResponseFunctionToolCall toolCall : toolCalls) {
      outputs.add(runToolActivity(toolCall, message, workflowContext));
    }
    return outputs;
  }

  public ResponseInputItem runToolActivity(
      ResponseFunctionToolCall toolCall,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    log.info("Invoking tool {}", toolCall.name());
    AgentTool tool = tools.get(toolCall.name());
    String output;
    try {
      JsonNode args = objectMapper.readTree(toolCall.arguments());
      args = applyThreadReplyDefaults(toolCall.name(), args, message);
      ToolContext toolContext = new ToolContext(this, message, workflowContext);
      if (tool == null && coderMcpClient != null) {
        String accountBase = CoderMcpClient.resolveAccountBase(message);
        tool =
            coderMcpClient
                .getAgentTool(accountBase, toolCall.name(), HIDDEN_CODER_MCP_TOOL_NAMES)
                .orElse(null);
      }
      if (tool == null) {
        output = "Unknown tool: " + toolCall.name();
      } else {
        output = tool.handler().apply(toolContext, args);
      }
    } catch (Exception e) {
      output = "Tool call failed: " + e.getMessage();
      log.warn("Tool call failed: {}", toolCall.name(), e);
    }

    ResponseInputItem.FunctionCallOutput toolOutput =
        ResponseInputItem.FunctionCallOutput.builder()
            .callId(toolCall.callId())
            .output(output)
            .build();
    return ResponseInputItem.ofFunctionCallOutput(toolOutput);
  }

  private JsonNode applyThreadReplyDefaults(
      String toolName, JsonNode args, IncomingMessage message) {
    if (toolName == null || args == null || message == null) {
      return args;
    }
    if (!SendTextAgentTool.TOOL_NAME.equals(toolName)) {
      return args;
    }
    if (args.hasNonNull("selectedMessageGuid")) {
      return args;
    }
    String replyTarget = resolveThreadRootGuid(message);
    if (replyTarget == null || replyTarget.isBlank()) {
      return args;
    }
    if (!(args instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode)) {
      return args;
    }
    objectNode.put("selectedMessageGuid", replyTarget);
    return objectNode;
  }

  private boolean shouldIncludeTool(AgentTool tool, IncomingMessage message) {
    MessageTransport transport = transportFor(message);
    if (SendReactionAgentTool.TOOL_NAME.equals(tool.name())) {
      return transport.supportsReactions();
    }
    if (BLUEBUBBLES_ONLY_TOOLS.contains(tool.name())
        && !IncomingMessage.TRANSPORT_BLUEBUBBLES.equals(transport.id())) {
      return false;
    }
    if (GROUP_ONLY_TOOLS.contains(tool.name())) {
      return message != null && message.isGroup();
    }
    if (Set.of(KubernetesReadOnlyAgentTool.TOOL_NAME, KubernetesPodLogsAgentTool.TOOL_NAME)
        .contains(tool.name())) {
      return message != null
          && !message.isGroup()
          && KUBERNETES_TOOL_ALLOWED_SENDER.equals(message.sender());
    }
    return true;
  }

  private List<AgentTool> availableTools(IncomingMessage message) {
    List<AgentTool> available = new ArrayList<>(tools.values());
    if (coderMcpClient != null) {
      String accountBase = CoderMcpClient.resolveAccountBase(message);
      available.addAll(coderMcpClient.getAgentTools(accountBase, HIDDEN_CODER_MCP_TOOL_NAMES));
    }
    return available;
  }

  public List<GeneratedImage> extractGeneratedImages(Response response) {
    if (response == null || response.output() == null) {
      return List.of();
    }
    List<GeneratedImage> images = new ArrayList<>();
    log.debug(
        "Extracting images from response - total of {} items in response",
        response.output().size());
    for (ResponseOutputItem item : response.output()) {
      if (item.imageGenerationCall().isEmpty()) {
        log.debug("Skipping item - not image generation call");
        continue;
      }
      ResponseOutputItem.ImageGenerationCall call = item.imageGenerationCall().get();
      log.info("Got an image igeneration item {}", call.id());
      if (!call.status().equals(ResponseOutputItem.ImageGenerationCall.Status.COMPLETED)) {
        log.warn("Image generation failed(bad status), status was : {}", call.status());
        continue;
      }
      String result = call.result().orElse(null);
      if (result == null || result.isBlank()) {
        log.warn("Image generation failed(blank result): {}", call.id());
        continue;
      }
      byte[] bytes = decodeImageResult(result.trim());
      if (bytes == null || bytes.length == 0) {
        log.warn("Image generation failed(empty bytes): {}", call.id());
        continue;
      }
      String id = call.id();
      String filename = "generated-" + (id != null ? id : UUID.randomUUID()) + ".png";
      log.info("Generated image for {}: {}", id, filename);
      images.add(new GeneratedImage(bytes, filename));
      if (images.size() >= MAX_GENERATED_IMAGES) {
        break;
      }
    }
    return images;
  }

  private byte[] decodeImageResult(String result) {
    if (result == null || result.isBlank()) {
      log.warn("Decode failed: empty string");
      return null;
    }
    String trimmed = result.trim();
    if (trimmed.startsWith("data:")) {
      log.debug("Decoding image data(inline)");
      int comma = trimmed.indexOf(',');
      if (comma > 0 && comma < trimmed.length() - 1) {
        return decodeBase64(trimmed.substring(comma + 1));
      }
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      log.debug("Need to download image bytes");
      return downloadBytes(trimmed);
    }
    log.debug("Doing b64 decode");
    return decodeBase64(trimmed);
  }

  private byte[] decodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      log.warn("Failed to decode base64 image result", e);
      return null;
    }
  }

  private byte[] downloadBytes(String url) {
    try (InputStream input = new URL(url).openStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (Exception e) {
      log.warn("Failed to download image result {}", url, e);
      return null;
    }
  }

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return;
    }
    String threadRootGuid = resolveThreadRootGuid(message);
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return;
    }
    List<String> imageUrls = resolveImageUrls(message);
    ConversationState.ThreadContext existing = state.getThreadContext(threadRootGuid);
    if ((imageUrls == null || imageUrls.isEmpty()) && existing != null) {
      imageUrls = existing.lastImageUrls();
    }
    String timestamp =
        message.timestamp() != null ? message.timestamp().toString() : Instant.now().toString();
    ConversationState.ThreadContext context =
        new ConversationState.ThreadContext(
            threadRootGuid,
            message.messageGuid(),
            message.text(),
            message.sender(),
            timestamp,
            imageUrls);
    state.recordThreadMessage(threadRootGuid, context);
  }

  private String resolveThreadRootGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      return message.threadOriginatorGuid();
    }
    return null;
  }

  public List<ResponseInputItem> buildConversationInput(
      List<ConversationTurn> history, IncomingMessage message) {
    List<ResponseInputItem> items = new ArrayList<>();
    boolean isGroupMessage = message.isGroup();
    items.add(ResponseInputItem.ofEasyInputMessage(systemMessage(isGroupMessage, message)));
    items.add(ResponseInputItem.ofEasyInputMessage(developerMessage(message)));
    if (history != null) {
      for (ConversationTurn turn : history) {
        items.add(ResponseInputItem.ofEasyInputMessage(turn.toEasyInputMessage()));
      }
    }
    items.add(ResponseInputItem.ofEasyInputMessage(userMessage(message)));
    findMyLocationContextMessage(message)
        .ifPresent(
            locationMessage -> items.add(ResponseInputItem.ofEasyInputMessage(locationMessage)));
    return items;
  }

  private Optional<EasyInputMessage> findMyLocationContextMessage(IncomingMessage message) {
    if (message == null || message.isGroup() || !message.isBlueBubblesTransport()) {
      return Optional.empty();
    }
    if (message.sender() == null || message.sender().isBlank()) {
      return Optional.empty();
    }
    try {
      FindMyFriendLocation location =
          bbHttpClientWrapper.getFindMyLocation(findMyLocationIdentifiers(message));
      String locationContext = formatFindMyLocationContext(location);
      if (locationContext == null || locationContext.isBlank()) {
        locationContext = missingFindMyLocationContext();
      }
      return Optional.of(
          EasyInputMessage.builder()
              .role(EasyInputMessage.Role.DEVELOPER)
              .content(locationContext)
              .build());
    } catch (Exception e) {
      log.warn(
          "Failed to fetch Find My location context for chat={} sender={}",
          message.chatGuid(),
          message.sender(),
          e);
      return Optional.of(
          EasyInputMessage.builder()
              .role(EasyInputMessage.Role.DEVELOPER)
              .content(missingFindMyLocationContext())
              .build());
    }
  }

  private List<String> findMyLocationIdentifiers(IncomingMessage message) {
    if (message == null || message.sender() == null || message.sender().isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> identifiers = new LinkedHashSet<>();
    if (accountIdentityAliasService != null) {
      identifiers.addAll(accountIdentityAliasService.accountBaseCandidates(message.sender()));
    }
    identifiers.add(message.sender());
    linkedWebsiteAccountEmail(message)
        .filter(email -> !email.isBlank())
        .filter(
            email -> identifiers.stream().noneMatch(existing -> existing.equalsIgnoreCase(email)))
        .ifPresent(identifiers::add);
    return List.copyOf(identifiers);
  }

  private void recordAccountAliases(IncomingMessage message) {
    if (accountIdentityAliasService == null) {
      return;
    }
    try {
      accountIdentityAliasService.recordMessageAliases(message);
    } catch (Exception e) {
      log.debug("Failed to record account identity aliases", e);
    }
  }

  private Optional<String> linkedWebsiteAccountEmail(IncomingMessage message) {
    if (websiteAccountService == null) {
      return Optional.empty();
    }
    try {
      return websiteAccountService.findLinkedAccountEmail(message);
    } catch (Exception e) {
      log.debug("Failed to resolve linked website account email for Find My lookup", e);
      return Optional.empty();
    }
  }

  private String missingFindMyLocationContext() {
    return "No current Find My location is available for the current iMessage sender. "
        + "If the user asks where they are, asks for real-time location-based information or updates, "
        + "or asks something that would benefit from knowing their current location, do not guess. "
        + "Tell them they can share their location via Find My if they want real-time location-based information or updates.";
  }

  private String formatFindMyLocationContext(FindMyFriendLocation location) {
    if (location == null) {
      return null;
    }
    List<Double> coordinates = location.getCoordinates();
    if (coordinates == null || coordinates.size() < 2) {
      return null;
    }
    Double latitude = coordinates.get(0);
    Double longitude = coordinates.get(1);
    if (latitude == null || longitude == null) {
      return null;
    }

    StringBuilder text =
        new StringBuilder(
            "Current Find My location context for the current iMessage sender. "
                + "Use this as background for location-aware answers, but do not mention it unless relevant. ");
    text.append("latitude=").append(latitude).append(" longitude=").append(longitude);
    appendReverseLocationLookupField(text, latitude, longitude);
    appendFindMyLocationField(text, "short_address", location.getShortAddress());
    appendFindMyLocationField(text, "long_address", location.getLongAddress());
    appendFindMyLocationField(text, "title", location.getTitle());
    if (location.getStatus() != null) {
      text.append(" status=").append(location.getStatus().getValue());
    }
    if (location.getLastUpdated() != null) {
      text.append(" last_updated=").append(Instant.ofEpochMilli(location.getLastUpdated()));
    }
    return text.toString();
  }

  private void appendReverseLocationLookupField(
      StringBuilder text, double latitude, double longitude) {
    try {
      reverseLocationLookup
          .reverseLookup(latitude, longitude)
          .map(location -> location.approximateAddress())
          .filter(address -> address != null && !address.isBlank())
          .ifPresent(
              address ->
                  appendFindMyLocationField(text, "reverse_geocoded_approximate_address", address));
    } catch (Exception e) {
      log.warn("Failed to append reverse geocoded location context", e);
    }
  }

  private void appendFindMyLocationField(StringBuilder text, String name, String value) {
    if (value != null && !value.isBlank()) {
      text.append(" ").append(name).append("=").append(value.replaceAll("\\s+", " ").trim());
    }
  }

  private EasyInputMessage systemMessage(boolean groupMessage, IncomingMessage message) {
    AssistantResponsiveness responsiveness =
        getAssistantResponsiveness(message != null ? message.chatGuid() : null);
    String responsivenessInstruction =
        switch (responsiveness) {
          case LESS_RESPONSIVE ->
              "Responsiveness: ALWAYS REPLY "
                  + NO_RESPONSE_TEXT
                  + " unless explicitly addressed, and do not issue any other response unless DIRECTLY ADDRESSED. No reacting unless directly asked. Don't engage in casual conversation, only reply to direct asks. Do not assume a message was meant for you unless you're directly addressed by name.";
          case MORE_RESPONSIVE ->
              "Responsiveness: more responsive. Act like an active participant, reply when helpful, and use reactions more freely. ";
          case SILENT ->
              "Responsiveness: silent. Only respond when explicitly invoked with the activation prefix 'Chat' (case-insensitive).";
          case DEFAULT -> "";
        };
    String transportInstruction =
        message != null && message.isLxmfTransport()
            ? "You are a chat assistant over LXMF on Reticulum. This transport currently supports one-on-one plain text only. Do not use reactions, attachments, generated images, group controls, or markdown. "
            : "You are a chat assistant for iMessage via BlueBubbles. "
                + "You can use reactions for quick acknowledgements and avoid spamming. "
                + IMESSAGE_FORMATTING_INSTRUCTION;
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.SYSTEM)
        .content(
            transportInstruction
                + (groupMessage
                    ? "Only respond when it is helpful or requested - this is a group message and not all messages are for you. You MUST ONLY respond if the message was directed to you or if your response will add useful and helpful information."
                    : "This is a one on one message with a user. You should respond to messages unless no reply is needed.")
                + "Never reply to your own messages."
                + responsivenessInstruction
                + "Use the "
                + MemoryGetAgentTool.TOOL_NAME
                + " tool when memory could improve your response (skip if no reply is needed or another tool is more appropriate). "
                + " Always ask the memory tool before directly asking the user to see if memory already has the answer to your question. "
                + "Send a natural language query to the tool describing what information may help you answer. "
                + "If no reply is needed, output exactly "
                + NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private EasyInputMessage developerMessage(IncomingMessage message) {
    if (message != null && message.isLxmfTransport()) {
      return EasyInputMessage.builder()
          .role(EasyInputMessage.Role.DEVELOPER)
          .content(
              "You may respond with plain text if that is sufficient. "
                  + "All outgoing LXMF text must be plain text only. Do not use markdown or formatting markers such as **, __, backticks, or markdown lists. "
                  + "LXMF support is currently minimal: one-on-one text only. Do not try to send reactions, images, attachments, GIFs, group changes, or thread replies. "
                  + "Only call "
                  + SendTextAgentTool.TOOL_NAME
                  + " when you specifically need to send an extra message; plain text is fine otherwise. "
                  + "Use available tools for tasks like calendars, memory, Coder, scheduled follow-ups, or lookups when asked. "
                  + "If the user asks the assistant to be more or less responsive, call "
                  + AssistantResponsivenessAgentTool.TOOL_NAME
                  + " to update the setting. The silent mode will only invoke responses when the message starts with 'Chat' (case-insensitive). "
                  + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                  + AssistantNameAgentTool.TOOL_NAME
                  + " after they explicitly agree. "
                  + "For Google Calendar requests, use the available calendar tools. If the account is not linked, call "
                  + ManageAccountsAgentTool.TOOL_NAME
                  + " to get an auth_url and have the user complete the OAuth flow in their browser. "
                  + "When the user shares information about themselves, or information that is helpful to remember, use the "
                  + MemorySaveAgentTool.TOOL_NAME
                  + " tool to persist that info. "
                  + "If asked to recall details about the user or prior interactions, or if memory could help answer a question, call "
                  + MemoryGetAgentTool.TOOL_NAME
                  + " before responding. "
                  + "If no reply is needed, output exactly "
                  + NO_RESPONSE_TEXT
                  + ".")
          .build();
    }
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.DEVELOPER)
        .content(
            "You may respond with plain text if that is sufficient. "
                + IMESSAGE_FORMATTING_INSTRUCTION
                + "Only call "
                + SendTextAgentTool.TOOL_NAME
                + " or "
                + SendReactionAgentTool.TOOL_NAME
                + " when you specifically need those actions; plain text is fine otherwise. "
                + "When sending a text, you may optionally apply an iMessage effect via the effect parameter, but use effects sparingly (e.g. happy_birthday for birthday wishes). "
                + "Use available tools for tasks like calendars or lookups when asked. "
                + "Use web_search for current info or external lookups when relevant. "
                + "If the user requests an image and has attached images, use those images as starting references for image generation. "
                + "If the user asks the assistant to be more or less responsive (especially in group chats), call "
                + AssistantResponsivenessAgentTool.TOOL_NAME
                + " to update the setting. The silent mode will only invoke responses when the message starts with 'Chat' (case-insensitive). "
                + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                + AssistantNameAgentTool.TOOL_NAME
                + " after they explicitly agree. "
                + "Use "
                + SearchConvoHistoryAgentTool.TOOL_NAME
                + " if you need to look up recent messages in this chat. "
                + "Use "
                + CurrentConversationInfoAgentTool.TOOL_NAME
                + " to see participants and metadata for the chat. "
                + "If the incoming message is part of a thread (replyToGuid or threadOriginatorGuid), reply in the same thread by setting selectedMessageGuid (and partIndex if provided). "
                + "Use "
                + GetThreadContextAgentTool.TOOL_NAME
                + " when asked about the last message or previously sent images in this thread. "
                + "For group chats, you can rename the conversation or set a group icon when requested. "
                + "When the user asks to log in, sign up, manage their web account, connect iMessage to the website, or see linked integrations on the website, call "
                + LinkWebsiteAccountAgentTool.TOOL_NAME
                + " and send the returned user_facing_text. Do not invent account links manually. "
                + "Incoming message context may include websiteAccountLinked and websiteAccountExactChatLinked for the current sender. When the user asks whether the current sender or another sender is linked to a website account, call "
                + GetWebsiteAccountLinkStatusAgentTool.TOOL_NAME
                + " before answering if the context is absent, ambiguous, or the user names a different sender. "
                + "Use "
                + SendGiphyAgentTool.TOOL_NAME
                + " to reply with a GIF when it would be more expressive than text. "
                + "If a tool is unavailable, ask the user for clarification or say it is not configured. "
                + "For Google Calendar requests, use calendar tools like "
                + ListCalendarsAgentTool.TOOL_NAME
                + ", "
                + ListEventsAgentTool.TOOL_NAME
                + ", "
                + SearchEventsAgentTool.TOOL_NAME
                + ", "
                + GetEventAgentTool.TOOL_NAME
                + ", "
                + CreateEventAgentTool.TOOL_NAME
                + ", "
                + UpdateEventAgentTool.TOOL_NAME
                + ", "
                + DeleteEventAgentTool.TOOL_NAME
                + ", "
                + RespondToEventAgentTool.TOOL_NAME
                + ", "
                + GetFreebusyAgentTool.TOOL_NAME
                + ", "
                + ListColorsAgentTool.TOOL_NAME
                + ", and "
                + GetCurrentTimeAgentTool.TOOL_NAME
                + ". If the account is not linked, call "
                + ManageAccountsAgentTool.TOOL_NAME
                + " to get an auth_url and have the user complete the OAuth flow in their browser. "
                + "If multiple calendar accounts are linked, pass account_key (the account id from manage_accounts list, or 'default') to the calendar tools to pick the right account; ask if ambiguous. "
                + "Prefer taking action over asking for confirmation when the user's intent is clear and the action is reversible or low-risk; ask a clarifying question only when required information is missing or the action is destructive, expensive, or sensitive. "
                + "For multi-step tasks, keep using tools in the same turn until the task is complete, blocked by a specific error, or waiting on external work. "
                + "For long-running work, first start or advance the work with tools, then use "
                + ScheduledEventTool.TOOL_NAME
                + " to create a concrete follow-up instead of merely saying you will check later. Include enough identifiers and context in the scheduled task to continue without asking the user again. "
                + "When a scheduled follow-up checks async work and finds it is still pending or running, it must call "
                + ScheduledEventTool.TOOL_NAME
                + " again before ending the turn to create another one-time follow-up, unless the work is complete, failed, canceled, expired, or the task text's max attempts or deadline has been reached. Include the current attempt count, deadline or callback expiration, task id, callback id when available, original user intent, current status, and exact status/log tool to call next. Do not notify the user on every pending poll unless there is a useful change. "
                + "When a tool starts external work that may not finish immediately, such as a Coder task, Coder workspace build, deployment, test run, or log wait, you must call "
                + ScheduledEventTool.TOOL_NAME
                + " in the same turn after the start succeeds if the user expects results or monitoring. Use a one-time delaySeconds follow-up by default. "
                + "Use "
                + ScheduledEventListTool.TOOL_NAME
                + " to inspect pending follow-ups and "
                + ScheduledEventDeleteTool.TOOL_NAME
                + " to cancel them when requested. "
                + "When the user asks whether Coder is linked or says Coder tools are missing, call "
                + CoderAuthAgentTool.TOOL_NAME
                + " with status before answering; do not infer Coder availability from prior turns or static tool names. "
                + "When the user asks what Coder tools are available, answer from the currently available tool names whose names start with "
                + CoderMcpClient.TOOL_PREFIX
                + " plus "
                + StartCoderAsyncTaskAgentTool.TOOL_NAME
                + "; "
                + CoderAuthAgentTool.TOOL_NAME
                + " is only for auth/status/revoke, not Coder work. "
                + "When the user asks to start, run, kick off, or watch a Coder AI/dev task, call "
                + StartCoderAsyncTaskAgentTool.TOOL_NAME
                + " with the full task prompt. This one tool creates the callback, selects the task template, starts the Coder task, and schedules a fallback check; do not call "
                + WorkflowCallbackService.TOOL_NAME
                + ", "
                + StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL
                + ", or "
                + ScheduledEventTool.TOOL_NAME
                + " separately for initial Coder AI task startup. "
                + "For other Coder workspace, template, file, shell, status, or log requests, use available Coder MCP tools whose names start with "
                + CoderMcpClient.TOOL_PREFIX
                + ". If Coder is needed but no Coder task/tool path is available, call "
                + CoderAuthAgentTool.TOOL_NAME
                + " with auth_url and ask the user to complete the login link. "
                + "For multi-step Coder requests, keep using tool calls in the current turn until the requested action is complete or blocked by a specific error. "
                + "If a Coder tool returns a validation error and you have enough information to correct it, call the needed Coder tools and retry in the same turn. "
                + "After starting a long-running Coder workspace build or other non-task Coder work, use "
                + ScheduledEventTool.TOOL_NAME
                + " to check status/results later when the user expects you to watch it; include the task/workspace identifier, original request, callback id when available, maximum watch deadline, attempt count, which Coder status/log tools to call, and an instruction to call "
                + ScheduledEventTool.TOOL_NAME
                + " again if the Coder work is still pending or running. "
                + "Do not say a Coder action is done, starting, or being watched until the matching Coder tool has succeeded; only promise future watching if you have created an explicit follow-up mechanism. "
                + "When the user shares information about themselves, or information that is helpful to remember "
                + "use the "
                + MemorySaveAgentTool.TOOL_NAME
                + " tool to persist that info. "
                + "Use the current conversation identity; do not ask for an identifier. "
                + "If asked to recall details about the user or prior interactions, or if memory could help answer a question, "
                + "call "
                + MemoryGetAgentTool.TOOL_NAME
                + " before responding. "
                + "If the user asks to correct or remove saved details and provides a memory_id, "
                + "call "
                + MemoryUpdateAgentTool.TOOL_NAME
                + " or "
                + MemoryDeleteAgentTool.TOOL_NAME
                + ". "
                + "If no reply is needed, output exactly "
                + NO_RESPONSE_TEXT
                + ". "
                + "If the incoming message starts with 'Reacted ', 'Loved ', 'Liked ', 'Disliked ', 'Questioned ', 'Emphasized ', 'Laughed at ' - reply "
                + NO_RESPONSE_TEXT
                + " unless the reaction directly answers a question you (the assistant) asked or implies the user needs clarification. These are just reactions to your prior message and do not necessarily indicate a response is needed. Use your best judgement but err on the side of being less verbose and not responding by using "
                + NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private EasyInputMessage userMessage(IncomingMessage message) {
    List<ResponseInputContent> content = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Incoming message");
    if (message.sender() != null && !message.sender().isBlank()) {
      text.append(" from ").append(message.sender());
    }
    if (message.sender() != null && !message.sender().isBlank()) {
      String knownName = getGlobalNameForSender(message.sender());
      if (knownName != null && !knownName.isBlank()) {
        text.append(" [sender name=").append(knownName).append("]");
      }
    }
    if (message.isGroup()) {
      text.append(" (group chat)");
    }
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      text.append(" [chatGuid=").append(message.chatGuid()).append("]");
    }
    appendWebsiteAccountLinkContext(text, message);
    if (message.messageGuid() != null && !message.messageGuid().isBlank()) {
      text.append(" [messageGuid=").append(message.messageGuid()).append("]");
    }
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      text.append(" [threadOriginatorGuid=").append(message.threadOriginatorGuid()).append("]");
    }
    if (resolveThreadRootGuid(message) != null) {
      text.append(" [threadReply=true]");
    }
    text.append(": ");
    if (message.text() != null && !message.text().isBlank()) {
      text.append(message.text());
    } else {
      text.append("[no text]");
    }
    List<String> imageUrls = resolveImageUrls(message);
    List<ResponseInputFile> files = resolveAttachmentFiles(message);
    if (!imageUrls.isEmpty()) {
      text.append(" [").append(imageUrls.size()).append(" image(s) attached]");
    }
    if (!files.isEmpty()) {
      text.append(" [").append(files.size()).append(" file(s) attached]");
    }
    content.add(
        ResponseInputContent.ofInputText(
            ResponseInputText.builder().text(text.toString()).build()));
    int added = 0;
    for (String url : imageUrls) {
      if (added >= MAX_IMAGE_ATTACHMENTS) {
        break;
      }
      content.add(
          ResponseInputContent.ofInputImage(
              ResponseInputImage.builder()
                  .detail(ResponseInputImage.Detail.AUTO)
                  .imageUrl(url)
                  .build()));
      added++;
    }
    int addedFiles = 0;
    for (ResponseInputFile file : files) {
      if (addedFiles >= MAX_FILE_ATTACHMENTS) {
        break;
      }
      content.add(ResponseInputContent.ofInputFile(file));
      addedFiles++;
    }
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.USER)
        .contentOfResponseInputMessageContentList(content)
        .build();
  }

  private List<String> resolveImageUrls(IncomingMessage message) {
    if (message.attachments() == null || message.attachments().isEmpty()) {
      return List.of();
    }
    return message.attachments().stream()
        .map(this::resolveAttachmentImageUrl)
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
  }

  private Optional<String> resolveAttachmentImageUrl(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    String mimeType = attachment.mimeType();
    if (mimeType != null && !mimeType.startsWith("image/")) {
      return Optional.empty();
    }
    if (attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
      if (attachment.dataUrl().startsWith("data:image/")) {
        return Optional.of(attachment.dataUrl());
      }
      return Optional.empty();
    }
    if (attachment.base64() != null && mimeType != null && mimeType.startsWith("image/")) {
      return Optional.of("data:" + mimeType + ";base64," + attachment.base64());
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return Optional.of(attachment.url());
    }
    if (attachment.guid() != null && !attachment.guid().isBlank()) {
      try {
        Path path = bbHttpClientWrapper.getAttachment(attachment.guid());
        if (path != null) {
          byte[] bytes = java.nio.file.Files.readAllBytes(path);
          String resolvedMime = mimeType;
          if (resolvedMime == null || resolvedMime.isBlank()) {
            try {
              resolvedMime = java.nio.file.Files.probeContentType(path);
            } catch (Exception ignored) {
              // best effort mime detection
            }
          }
          if (resolvedMime == null || resolvedMime.isBlank()) {
            resolvedMime = "image/png";
          }
          String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
          try {
            java.nio.file.Files.deleteIfExists(path);
          } catch (Exception ignored) {
            // best effort cleanup
          }
          return Optional.of("data:" + resolvedMime + ";base64," + base64);
        }
      } catch (Exception e) {
        log.warn("Failed to download image attachment {}", attachment.guid(), e);
      }
    }
    return Optional.empty();
  }

  private List<ResponseInputFile> resolveAttachmentFiles(IncomingMessage message) {
    if (message.attachments() == null || message.attachments().isEmpty()) {
      return List.of();
    }
    return message.attachments().stream()
        .map(this::resolveAttachmentFile)
        .flatMap(Optional::stream)
        .collect(Collectors.toList());
  }

  private void appendWebsiteAccountLinkContext(StringBuilder text, IncomingMessage message) {
    if (websiteAccountService == null || message == null) {
      return;
    }
    try {
      WebsiteAccountService.SenderLinkStatus status = websiteAccountService.getLinkStatus(message);
      if (status.accountBase() == null || status.accountBase().isBlank()) {
        return;
      }
      text.append(" [websiteAccountLinked=").append(status.linked()).append("]");
      text.append(" [websiteAccountExactChatLinked=").append(status.exactChatLinked()).append("]");
      if (status.modelAccess() != null) {
        text.append(" [modelPlan=").append(status.modelAccess().getPlan()).append("]");
        text.append(" [modelPremium=").append(status.modelAccess().getIsPremium()).append("]");
        text.append(" [currentModel=").append(status.modelAccess().getCurrentModel()).append("]");
      }
    } catch (Exception e) {
      log.debug("Failed to load website account link context", e);
    }
  }

  private Optional<ResponseInputFile> resolveAttachmentFile(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    String mimeType = attachment.mimeType();
    if (mimeType != null && mimeType.startsWith("image/")) {
      return Optional.empty();
    }
    String filename = attachment.filename();
    if (filename == null || filename.isBlank()) {
      filename = "attachment";
    }
    String fileData = null;
    if (attachment.guid() != null && !attachment.guid().isBlank()) {
      try {
        Path path = bbHttpClientWrapper.getAttachment(attachment.guid());
        if (path != null) {
          byte[] bytes = java.nio.file.Files.readAllBytes(path);
          fileData = java.util.Base64.getEncoder().encodeToString(bytes);
          try {
            java.nio.file.Files.deleteIfExists(path);
          } catch (Exception ignored) {
            // best effort cleanup
          }
          if (path.getFileName() != null && !path.getFileName().toString().isBlank()) {
            filename = path.getFileName().toString();
          }
        }
      } catch (Exception e) {
        log.warn("Failed to download attachment {}", attachment.guid(), e);
      }
    }
    if (fileData == null && attachment.base64() != null && !attachment.base64().isBlank()) {
      fileData = attachment.base64();
    }
    if (fileData == null && attachment.dataUrl() != null && !attachment.dataUrl().isBlank()) {
      String dataUrl = attachment.dataUrl();
      int comma = dataUrl.indexOf(',');
      if (comma > 0) {
        fileData = dataUrl.substring(comma + 1);
      }
    }
    if (fileData == null || fileData.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ResponseInputFile.builder().fileData(fileData).filename(filename).build());
  }

  private void registerBuiltInTools() {
    registerTool(new SendTextAgentTool().getTool());
    registerTool(new SendReactionAgentTool().getTool());
    registerTool(new SearchConvoHistoryAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new CurrentConversationInfoAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new RenameConversationAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new SetGroupIconAgentTool(bbHttpClientWrapper, openAiSupplier).getTool());
    registerTool(
        new SendGiphyAgentTool(bbHttpClientWrapper, giphyClient, openAiSupplier).getTool());
    registerTool(new AssistantResponsivenessAgentTool().getTool());
    registerTool(new AssistantNameAgentTool().getTool());
    registerTool(new MemorySaveAgentTool(mem0Client).getTool());
    registerTool(new MemoryGetAgentTool(mem0Client).getTool());
    registerTool(new MemoryUpdateAgentTool(mem0Client).getTool());
    registerTool(new MemoryDeleteAgentTool(mem0Client).getTool());
    registerTool(new ListCalendarsAgentTool(gcalClient).getTool());
    registerTool(new ListEventsAgentTool(gcalClient).getTool());
    registerTool(new SearchEventsAgentTool(gcalClient).getTool());
    registerTool(new GetEventAgentTool(gcalClient).getTool());
    registerTool(new CreateEventAgentTool(gcalClient).getTool());
    registerTool(new UpdateEventAgentTool(gcalClient).getTool());
    registerTool(new DeleteEventAgentTool(gcalClient).getTool());
    registerTool(new RespondToEventAgentTool(gcalClient).getTool());
    registerTool(new GetFreebusyAgentTool(gcalClient).getTool());
    registerTool(new ManageAccountsAgentTool(gcalClient).getTool());
    registerTool(new ListColorsAgentTool(gcalClient).getTool());
    registerTool(new GetCurrentTimeAgentTool(gcalClient).getTool());
    if (coderMcpClient != null) {
      registerTool(new CoderAuthAgentTool(coderMcpClient).getTool());
    }
    if (coderMcpClient != null
        && workflowCallbackService != null
        && coderAsyncTaskStartStore != null) {
      registerTool(
          new StartCoderAsyncTaskAgentTool(
                  coderMcpClient,
                  workflowCallbackService,
                  coderAsyncTaskStartStore,
                  cadenceWorkflowLauncher)
              .getTool());
    }
    if (websiteAccountService != null) {
      registerTool(new LinkWebsiteAccountAgentTool(websiteAccountService).getTool());
      registerTool(new GetWebsiteAccountLinkStatusAgentTool(websiteAccountService).getTool());
    }
    registerTool(new KubernetesReadOnlyAgentTool(objectMapper).getTool());
    registerTool(new KubernetesPodLogsAgentTool(objectMapper).getTool());
    registerTool(new GetThreadContextAgentTool(bbHttpClientWrapper).getTool());
    registerTool(new ScheduledEventTool(cadenceWorkflowLauncher).getTool());
    registerTool(new ScheduledEventListTool(cadenceWorkflowLauncher).getTool());
    registerTool(new ScheduledEventDeleteTool(cadenceWorkflowLauncher).getTool());
  }

  private void registerTool(AgentTool tool) {
    tools.put(tool.name(), tool);
  }

  public AssistantResponsiveness getAssistantResponsiveness(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return AssistantResponsiveness.DEFAULT;
    }
    return agentSettingsStore
        .findAssistantResponsiveness(chatGuid)
        .orElse(AssistantResponsiveness.DEFAULT);
  }

  public void setAssistantResponsiveness(String chatGuid, AssistantResponsiveness responsiveness) {
    if (chatGuid == null || chatGuid.isBlank() || responsiveness == null) {
      return;
    }
    if (responsiveness == AssistantResponsiveness.DEFAULT) {
      agentSettingsStore.deleteAssistantResponsiveness(chatGuid);
      return;
    }
    agentSettingsStore.saveAssistantResponsiveness(chatGuid, responsiveness);
  }

  public String getGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return null;
    }
    return agentSettingsStore.findGlobalName(sender).orElse(null);
  }

  public void setGlobalNameForSender(String sender, String name) {
    if (sender == null || sender.isBlank() || name == null || name.isBlank()) {
      return;
    }
    agentSettingsStore.saveGlobalName(sender, name);
  }

  public void removeGlobalNameForSender(String sender) {
    if (sender == null || sender.isBlank()) {
      return;
    }
    agentSettingsStore.deleteGlobalName(sender);
  }
}
