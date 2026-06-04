package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandler;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.llm.LlmProvider;
import io.breland.bbagent.server.agent.llm.OpenAiResponsesLlmProvider;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.gcal.*;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.*;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Duration;
import java.time.Instant;
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
  public static final String IMESSAGE_SERVICE = "iMessage";

  @Getter private final ObjectMapper objectMapper;
  @Getter private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  private final MessageTransportRegistry transportRegistry;
  private final AgentProfileService profileService;
  private final CadenceIncomingMessageHandler incomingMessageHandler;
  private final AgentToolActivityRunner toolActivityRunner;
  private final WorkflowResponseGate workflowResponseGate;
  private final AgentResponseCreator responseCreator;
  private final ConversationThreadContextRecorder threadContextRecorder;

  private OpenAIClient openAIClient;
  private final Supplier<OpenAIClient> openAiSupplier =
      () -> {
        if (openAIClient == null) {
          openAIClient =
              OpenAIOkHttpClient.fromEnv().withOptions(b -> b.timeout(Duration.ofSeconds(120)));
        }
        return openAIClient;
      };

  private final MessageResponseRateLimitService messageResponseRateLimitService;
  private final MessageResponseLimitNoticeFactory messageResponseLimitNoticeFactory;
  private final NativeAppSessionService nativeAppSessionService;

  @Value("${website.base-url:http://localhost:8080}")
  private String websiteBaseUrl = "http://localhost:8080";

  @Value(
      "${bbagent.terms.acceptance.responses-model:"
          + TermsAgreementValidator.DEFAULT_RESPONSES_MODEL
          + "}")
  private String termsAcceptanceResponsesModel = TermsAgreementValidator.DEFAULT_RESPONSES_MODEL;

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
      @Nullable NativeAppSessionService nativeAppSessionService,
      ModelPicker modelPicker) {
    if (openAiClient != null) {
      this.openAIClient = openAiClient;
    }
    this.messageResponseLimitNoticeFactory =
        new MessageResponseLimitNoticeFactory(websiteAccountService);
    this.transportRegistry =
        transportRegistry != null
            ? transportRegistry
            : MessageTransportRegistry.blueBubblesOnly(bbHttpClientWrapper);
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    TermsAgreementValidator termsAgreementValidator =
        new TermsAgreementValidator(
            openAiSupplier, this.objectMapper, () -> termsAcceptanceResponsesModel);
    this.profileService = profileService;
    this.messageResponseRateLimitService = messageResponseRateLimitService;
    this.nativeAppSessionService = nativeAppSessionService;
    this.workflowResponseGate = new WorkflowResponseGate(conversations);
    this.threadContextRecorder = new ConversationThreadContextRecorder(attachmentInputBuilder);
    LlmProvider llmProvider = new OpenAiResponsesLlmProvider(openAiSupplier, modelPicker);
    CadenceWorkflowLauncher workflowLauncher =
        Objects.requireNonNull(cadenceWorkflowLauncher, "cadenceWorkflowLauncher");
    AgentToolRegistry toolRegistry =
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
    this.responseCreator =
        new AgentResponseCreator(
            modelPicker, toolRegistry, llmProvider, operationalMetricsService, profileService);
    this.toolActivityRunner =
        new AgentToolActivityRunner(
            this, this.objectMapper, profileService, toolRegistry, agentMetricsService);
    this.incomingMessageHandler =
        new CadenceIncomingMessageHandler(
            this,
            conversations,
            profileService,
            this.transportRegistry,
            bbHttpClientWrapper,
            workflowLauncher,
            agentMetricsService,
            this::termsUrl,
            termsAgreementValidator);
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
        null,
        modelPicker);
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    return incomingMessageHandler.computeConversationState(chatId, message);
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    incomingMessageHandler.handleIncomingMessage(claimNativeAppStartToken(message));
  }

  private IncomingMessage claimNativeAppStartToken(IncomingMessage message) {
    if (nativeAppSessionService == null) {
      return message;
    }
    try {
      return nativeAppSessionService.claimStartToken(message);
    } catch (Exception e) {
      log.warn("native_app_start_token_claim_failed", e);
      return message;
    }
  }

  private String termsUrl() {
    String baseUrl = websiteBaseUrl == null ? "" : websiteBaseUrl.trim();
    if (baseUrl.isBlank()) {
      return "/terms";
    }
    return StringUtils.removeEnd(baseUrl, "/") + "/terms";
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    return workflowResponseGate.canSendResponses(workflowContext);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    return workflowResponseGate.canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  public void recordAssistantTurnForCurrentMessage(
      IncomingMessage message, String content, AgentWorkflowContext workflowContext) {
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid == null) {
      return;
    }
    if (content == null || content.isBlank()) {
      return;
    }
    if (!canSendResponses(workflowContext)) {
      return;
    }
    ConversationState state = conversations.get(chatGuid);
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
    String replyTarget =
        transport.supportsThreadReplies() ? ThreadReplySupport.threadRootGuid(message) : null;
    return transport.sendText(message, new OutgoingTextMessage(text, replyTarget, null, null));
  }

  public boolean sendTextUnmetered(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    return transportRegistry.resolve(message).sendText(message, outgoingMessage);
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
      log.warn("Failed to check message response rate limit for {}", message, e);
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
      log.warn("Failed to consume message response rate limit for {}", message, e);
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
    String text = messageResponseLimitNoticeFactory.rateLimitExceededText(message, status);
    if (sendThreadAwareTextUnmetered(message, text)) {
      recordAssistantTurnForCurrentMessage(message, text, workflowContext);
    }
  }

  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    return responseCreator.createResponse(inputItems, message, workflowContext);
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
    return toolActivityRunner.run(toolCall, message, workflowContext);
  }

  static String truncateToolOutputForModel(String output, String toolName) {
    return AgentToolActivityRunner.truncateToolOutputForModel(output, toolName);
  }

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    threadContextRecorder.updateThreadContext(state, message);
  }
}
