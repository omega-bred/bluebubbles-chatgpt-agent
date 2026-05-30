package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.*;
import com.uber.cadence.workflow.Workflow;
import io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandler;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.llm.LlmProvider;
import io.breland.bbagent.server.agent.llm.LlmRequest;
import io.breland.bbagent.server.agent.llm.OpenAiResponsesLlmProvider;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.*;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.*;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import io.breland.bbagent.server.ratelimit.RateLimitStatus;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BBMessageAgent {

  public static final int MAX_HISTORY = 50;
  public static final String NO_RESPONSE_TEXT = "NO_RESPONSE";
  public static final String AGENT_PHONE_NUMBER = "+1 (415) 867-4956";
  public static final String TERMS_ACCEPTANCE_REPLY =
      "Before I can help, you need to agree to the Terms of Use: %s\n\n"
          + "Reply YES to confirm that you are at least 18, agree not to use this service for spam, abuse, illegal activity, or harmful content, understand that AI output may be inaccurate, and accept that the service is provided as-is with no refunds, no SLA, and no liability guarantees.";
  public static final String TERMS_ACCEPTED_REPLY =
      "Thanks, you're all set. Send your request and I'll help.";
  private static final int MAX_TOOL_OUTPUT_CHARS = 24_000;
  private static final int TOOL_OUTPUT_EDGE_CHARS = 12_000;
  public static final String IMESSAGE_SERVICE = "iMessage";

  @Getter private final ObjectMapper objectMapper;
  @Getter private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  private final MessageTransportRegistry transportRegistry;
  private final AgentProfileService profileService;
  private final AgentAttachmentInputBuilder attachmentInputBuilder;
  private final AgentToolRegistry toolRegistry;
  private final CadenceIncomingMessageHandler incomingMessageHandler;

  private OpenAIClient openAIClient;
  private final Supplier<OpenAIClient> openAiSupplier =
      () -> {
        if (openAIClient == null) {
          openAIClient =
              OpenAIOkHttpClient.fromEnv().withOptions(b -> b.timeout(Duration.ofSeconds(120)));
        }
        return openAIClient;
      };

  private final WebsiteAccountService websiteAccountService;
  private final ModelPicker modelPicker;
  private final LlmProvider llmProvider;
  private final AgentMetricsService agentMetricsService;
  private final OperationalMetricsService operationalMetricsService;
  private final MessageResponseRateLimitService messageResponseRateLimitService;

  @Value("${website.base-url:http://localhost:8080}")
  private String websiteBaseUrl = "http://localhost:8080";

  @Autowired
  public BBMessageAgent(
      @Nullable OpenAIClient openAiClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      AgentProfileService profileService,
      AgentAttachmentInputBuilder attachmentInputBuilder,
      MessageTransportRegistry transportRegistry,
      @Nullable ObjectMapper objectMapper,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      @Nullable AgentMetricsService agentMetricsService,
      @Nullable FeedbackService feedbackService,
      @Nullable MessageResponseRateLimitService messageResponseRateLimitService,
      @Nullable OperationalMetricsService operationalMetricsService,
      ModelPicker modelPicker) {
    if (openAiClient != null) {
      this.openAIClient = openAiClient;
    }
    this.websiteAccountService = websiteAccountService;
    this.transportRegistry =
        transportRegistry != null
            ? transportRegistry
            : MessageTransportRegistry.blueBubblesOnly(bbHttpClientWrapper);
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.profileService = profileService;
    this.attachmentInputBuilder = attachmentInputBuilder;
    this.agentMetricsService = agentMetricsService;
    this.operationalMetricsService = operationalMetricsService;
    this.messageResponseRateLimitService = messageResponseRateLimitService;
    this.modelPicker = modelPicker;
    this.llmProvider = new OpenAiResponsesLlmProvider(openAiSupplier, modelPicker);
    CadenceWorkflowLauncher workflowLauncher =
        Objects.requireNonNull(cadenceWorkflowLauncher, "cadenceWorkflowLauncher");
    this.toolRegistry =
        new AgentToolRegistry(
            bbHttpClientWrapper,
            mem0Client,
            gcalClient,
            websiteAccountService,
            giphyClient,
            this.transportRegistry,
            this.objectMapper,
            openAiSupplier,
            feedbackService,
            messageResponseRateLimitService,
            workflowLauncher,
            profileService::resolveOrCreateAccountId,
            operationalMetricsService,
            modelPicker.modelAccessService());
    this.incomingMessageHandler =
        new CadenceIncomingMessageHandler(
            this,
            conversations,
            profileService,
            this.transportRegistry,
            bbHttpClientWrapper,
            workflowLauncher,
            agentMetricsService,
            this::termsUrl);
  }

  public BBMessageAgent(
      @Nullable OpenAIClient openAiClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      Mem0Client mem0Client,
      GcalClient gcalClient,
      WebsiteAccountService websiteAccountService,
      GiphyClient giphyClient,
      AgentProfileService profileService,
      AgentAttachmentInputBuilder attachmentInputBuilder,
      MessageTransportRegistry transportRegistry,
      @Nullable ObjectMapper objectMapper,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      @Nullable AgentMetricsService agentMetricsService,
      @Nullable FeedbackService feedbackService,
      @Nullable MessageResponseRateLimitService messageResponseRateLimitService,
      ModelPicker modelPicker) {
    this(
        openAiClient,
        bbHttpClientWrapper,
        mem0Client,
        gcalClient,
        websiteAccountService,
        giphyClient,
        profileService,
        attachmentInputBuilder,
        transportRegistry,
        objectMapper,
        cadenceWorkflowLauncher,
        agentMetricsService,
        feedbackService,
        messageResponseRateLimitService,
        null,
        modelPicker);
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    return incomingMessageHandler.computeConversationState(chatId, message);
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    incomingMessageHandler.handleIncomingMessage(message);
  }

  private String termsUrl() {
    String baseUrl = websiteBaseUrl == null ? "" : websiteBaseUrl.trim();
    if (baseUrl.isBlank()) {
      return "/terms";
    }
    return StringUtils.removeEnd(baseUrl, "/") + "/terms";
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    if (workflowContext == null) {
      return true;
    }
    String currentRunId = null;
    try {
      currentRunId = Workflow.getWorkflowInfo().getRunId();
    } catch (Error e) {
      if (e.getMessage() == null || !e.getMessage().contains("non workflow")) {
        throw e;
      }
      // Running outside Cadence, such as in unit tests or direct tool calls.
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
    if (workflowContext.chatGuid() == null || workflowContext.chatGuid().isBlank()) {
      return true;
    }
    ConversationState state = conversations.get(workflowContext.chatGuid());
    if (state == null) {
      return true;
    }
    synchronized (state) {
      String latestWorkflowMessageGuid = state.getLatestWorkflowMessageGuid();
      if (StringUtils.isNotBlank(latestWorkflowMessageGuid)
          && StringUtils.isNotBlank(workflowContext.messageGuid())
          && !latestWorkflowMessageGuid.equals(workflowContext.messageGuid())) {
        return false;
      }
      if (currentRunId == null || currentRunId.isBlank()) {
        return latestWorkflowMessageGuid == null
            || latestWorkflowMessageGuid.isBlank()
            || workflowContext.messageGuid() == null
            || latestWorkflowMessageGuid.equals(workflowContext.messageGuid());
      }
      String latestWorkflowRunId = state.getLatestWorkflowRunId();

      // can be null until we persist state in a real db.
      return latestWorkflowRunId == null || latestWorkflowRunId.equals(currentRunId);
    }
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
      recordIncomingTurnsForResponse(state, message);
      state.addTurn(ConversationTurn.assistant(content, Instant.now()));
    }
  }

  public void recordIncomingTurnsForResponse(ConversationState state, IncomingMessage message) {
    if (state == null) {
      return;
    }
    state.recordPendingIncomingTurnsToHistory();
    state.recordIncomingTurnIfAbsent(message);
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  public boolean sendThreadAwareText(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext) {
    if (message == null || text == null || text.isBlank()) {
      return false;
    }
    if (!consumeMessageResponseQuota(message, workflowContext)) {
      return false;
    }
    return sendThreadAwareTextUnmetered(message, text);
  }

  public boolean sendThreadAwareTextUnmetered(IncomingMessage message, String text) {
    if (message == null || text == null || text.isBlank()) {
      return false;
    }
    MessageTransport transport = transportRegistry.resolve(message);
    String replyTarget = transport.supportsThreadReplies() ? resolveThreadRootGuid(message) : null;
    return transport.sendText(message, new OutgoingTextMessage(text, replyTarget, null, null));
  }

  public boolean sendTextFromTool(
      IncomingMessage message,
      OutgoingTextMessage outgoingMessage,
      AgentWorkflowContext workflowContext) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    if (!consumeMessageResponseQuota(message, workflowContext)) {
      return false;
    }
    return transportRegistry.resolve(message).sendText(message, outgoingMessage);
  }

  public boolean sendReactionFromTool(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex,
      AgentWorkflowContext workflowContext) {
    MessageTransport transport = reactionTransport(message, reaction, workflowContext);
    if (transport == null) {
      return false;
    }
    return transport.sendReaction(
        message, conversationId, selectedMessageGuid, reaction, partIndex);
  }

  private @Nullable MessageTransport reactionTransport(
      IncomingMessage message, String reaction, AgentWorkflowContext workflowContext) {
    if (message == null || reaction == null || reaction.isBlank()) {
      return null;
    }
    MessageTransport transport = transportRegistry.resolve(message);
    if (!transport.supportsReactions()) {
      return null;
    }
    if (!consumeMessageResponseQuota(message, workflowContext)) {
      return null;
    }
    return transport;
  }

  public boolean notifyIfMessageResponseLimitExceeded(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (messageResponseRateLimitService == null || message == null) {
      return false;
    }
    if (isCanaryAccount(message)) {
      return false;
    }
    try {
      MessageResponseRateLimitService.MessageResponseLimitStatus status =
          messageResponseRateLimitService.statusFor(message);
      if (!status.tracked() || status.rateLimit() == null || !status.rateLimit().exhausted()) {
        return false;
      }
      sendRateLimitExceededNotice(message, status, workflowContext);
      return true;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to check message response rate limit for {}",
          IncomingMessage.logSummary(message),
          e);
      return false;
    }
  }

  public boolean consumeMessageResponseQuota(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (messageResponseRateLimitService == null) {
      return true;
    }
    if (isCanaryAccount(message)) {
      return true;
    }
    if (!canSendResponses(workflowContext)) {
      return false;
    }
    try {
      RateLimitDecision decision = messageResponseRateLimitService.tryConsume(message);
      if (decision.allowed()) {
        return true;
      }
      sendRateLimitExceededNotice(
          message, messageResponseRateLimitService.statusFor(message), workflowContext);
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to consume message response rate limit for {}",
          IncomingMessage.logSummary(message),
          e);
      return true;
    }
  }

  private void sendRateLimitExceededNotice(
      IncomingMessage message,
      MessageResponseRateLimitService.MessageResponseLimitStatus status,
      AgentWorkflowContext workflowContext) {
    if (message == null || status == null || !canSendResponses(workflowContext)) {
      return;
    }
    String text = rateLimitExceededText(message, status);
    if (sendThreadAwareTextUnmetered(message, text)) {
      recordAssistantTurnForCurrentMessage(message, text, workflowContext);
    }
  }

  private String rateLimitExceededText(
      IncomingMessage message, MessageResponseRateLimitService.MessageResponseLimitStatus status) {
    RateLimitStatus rateLimit = status.rateLimit();
    String resetAt =
        rateLimit == null
            ? "the next UTC month"
            : DateTimeFormatter.ISO_INSTANT.format(rateLimit.windowEnd());
    long limit = rateLimit == null ? 0L : rateLimit.limit();
    if (!status.premium()) {
      StringBuilder text =
          new StringBuilder(
              "You've hit the free monthly limit of "
                  + limit
                  + " messages. Premium accounts currently get 5,000 messages per month. ");
      createUpgradeLinkText(message)
          .ifPresentOrElse(
              text::append,
              () ->
                  text.append(
                      "Link this chat identity to the website and upgrade to keep chatting this month. "));
      text.append("Your free limit resets at ").append(resetAt).append(".");
      return text.toString();
    }
    return "You've hit the premium monthly limit of "
        + limit
        + " messages. Your limit resets at "
        + resetAt
        + ".";
  }

  private Optional<String> createUpgradeLinkText(IncomingMessage message) {
    if (websiteAccountService == null || message == null) {
      return Optional.empty();
    }
    try {
      WebsiteAccountService.CreatedLinkToken link = websiteAccountService.createLinkToken(message);
      return Optional.of(
          "Open this link to log in or sign up, connect this chat identity, and upgrade: "
              + link.url()
              + " ");
    } catch (RuntimeException e) {
      log.warn("Failed to create upgrade account link for rate limit notice", e);
      return Optional.empty();
    }
  }

  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> requestInputItems =
        modelPicker.shouldSquashDeveloperMessagesIntoSystem(message)
            ? ResponseInputMessages.squashDeveloperMessagesIntoSystem(inputItems)
            : inputItems;
    ModelAccessService.ModelAccess modelAccess = modelPicker.resolveModelAccess(message);
    List<AgentTool> tools = toolRegistry.availableTools(message);
    LlmRequest request =
        new LlmRequest(modelAccess, requestInputItems, tools, message, workflowContext);
    long startedNanos = 0L;
    try {
      startedNanos = System.nanoTime();
      log.info(
          "Creating model response chat={} messageGuid={} workflowId={} provider={} model={} inputItems={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          modelAccess.provider(),
          modelAccess.responsesModel(),
          requestInputItems.size());
      log.trace("Final LLM request: {}", request);
      Response response = llmProvider.createResponse(request);
      recordLlmCallMetric(message, modelAccess, true, null, startedNanos);
      log.info(
          "Created model response chat={} messageGuid={} workflowId={} elapsedMs={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          elapsedMillis(startedNanos));
      return response;
    } catch (RuntimeException e) {
      recordLlmCallMetric(
          message, modelAccess, false, OperationalMetricsService.failureType(e), startedNanos);
      log.warn(
          "LLM response failed chat={} messageGuid={} workflowId={} provider={} model={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          modelAccess.provider(),
          modelAccess.responsesModel(),
          e);
      return null;
    }
  }

  private void recordLlmCallMetric(
      IncomingMessage message,
      ModelAccessService.ModelAccess modelAccess,
      boolean success,
      @Nullable String failureType,
      long startedNanos) {
    if (operationalMetricsService == null || startedNanos <= 0L) {
      return;
    }
    if (isCanaryAccount(message)) {
      return;
    }
    try {
      operationalMetricsService.recordLlmCall(
          message == null ? "unknown" : message.metricTransport(),
          "agent_response",
          modelAccess == null ? "unknown" : modelAccess.provider(),
          modelAccess == null ? "unknown" : modelAccess.responsesModel(),
          success,
          failureType,
          Duration.ofNanos(System.nanoTime() - startedNanos));
    } catch (RuntimeException e) {
      log.warn("Failed to record LLM call metric", e);
    }
  }

  private boolean isCanaryAccount(IncomingMessage message) {
    if (message == null || profileService == null) {
      return false;
    }
    return profileService.isCanaryAccount(message);
  }

  public ResponseInputItem runToolActivity(
      ResponseFunctionToolCall toolCall,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    log.info("Invoking tool {}", toolCall.name());
    AgentToolRegistry.ResolvedTool resolvedTool =
        toolRegistry.resolveTool(toolCall.name(), message);
    AgentTool tool = resolvedTool.tool();
    String output;
    String failureType;
    String toolCategory = toolRegistry.toolCategory(toolCall.name());
    boolean success = false;
    Instant startedAt = Instant.now();
    try {
      JsonNode args = objectMapper.readTree(toolCall.arguments());
      args = applyThreadReplyDefaults(toolCall.name(), args, message);
      ToolContext toolContext = new ToolContext(this, profileService, message, workflowContext);
      if (tool == null) {
        output = "Unknown tool: " + toolCall.name();
        failureType = "unknown_tool";
      } else {
        output =
            truncateToolOutputForModel(tool.handler().apply(toolContext, args), toolCall.name());
        failureType = classifyToolFailure(output);
        success = failureType == null;
      }
    } catch (Exception e) {
      output = "Tool call failed: " + e.getMessage();
      failureType = "exception";
      log.warn("Tool call failed: {}", toolCall.name(), e);
    }
    long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
    recordToolCallMetric(
        toolCall.name(), message, success, failureType, durationMillis, toolCategory);

    ResponseInputItem.FunctionCallOutput toolOutput =
        ResponseInputItem.FunctionCallOutput.builder()
            .callId(toolCall.callId())
            .output(output)
            .build();
    return ResponseInputItem.ofFunctionCallOutput(toolOutput);
  }

  static String truncateToolOutputForModel(String output, String toolName) {
    if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) {
      return output;
    }
    String safeToolName = StringUtils.defaultIfBlank(toolName, "tool");
    int omitted = output.length() - (TOOL_OUTPUT_EDGE_CHARS * 2);
    return output.substring(0, TOOL_OUTPUT_EDGE_CHARS)
        + "\n\n["
        + safeToolName
        + " output truncated for model context; omitted "
        + omitted
        + " characters. Re-run the tool with narrower filters, lower limits, or fewer log lines if more detail is needed.]\n\n"
        + output.substring(output.length() - TOOL_OUTPUT_EDGE_CHARS);
  }

  private void recordToolCallMetric(
      String toolName,
      IncomingMessage message,
      boolean success,
      @Nullable String failureType,
      long durationMillis,
      String toolCategory) {
    if (agentMetricsService == null) {
      return;
    }
    try {
      agentMetricsService.recordToolCall(
          new AgentToolMetricEvent(
              message, toolName, toolCategory, success, failureType, durationMillis));
    } catch (RuntimeException e) {
      log.warn("Failed to record tool metric for {}", toolName, e);
    }
  }

  private String classifyToolFailure(String output) {
    String normalized = StringUtils.trimToEmpty(output).toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return null;
    }
    if (normalized.startsWith("tool call failed:")) {
      return "exception";
    }
    if (normalized.startsWith("unknown tool:")) {
      return "unknown_tool";
    }
    if (normalized.startsWith("failed:")
        || normalized.startsWith("failed ")
        || normalized.startsWith("error:")
        || normalized.startsWith("unable to ")) {
      return "tool_error";
    }
    return null;
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

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return;
    }
    String threadRootGuid = resolveThreadRootGuid(message);
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return;
    }
    List<String> imageUrls = attachmentInputBuilder.resolveImageUrls(message);
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
}
