package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandler;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

  private final MessageResponseRateLimitService messageResponseRateLimitService;
  private final MessageResponseLimitNoticeFactory messageResponseLimitNoticeFactory;
  private final NativeAppSessionService nativeAppSessionService;
  private final String websiteBaseUrl;

  public BBMessageAgent(
      BBHttpClientWrapper bbHttpClientWrapper,
      AgentProfileService profileService,
      AgentAttachmentInputBuilder attachmentInputBuilder,
      MessageTransportRegistry transportRegistry,
      ObjectMapper objectMapper,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      AgentMetricsService agentMetricsService,
      MessageResponseRateLimitService messageResponseRateLimitService,
      NativeAppSessionService nativeAppSessionService,
      ConversationJournalService conversationJournalService,
      AgentToolRegistry toolRegistry,
      AgentResponseCreator responseCreator,
      TermsAgreementValidator termsAgreementValidator,
      MessageResponseLimitNoticeFactory messageResponseLimitNoticeFactory,
      @Value("${website.base-url:http://localhost:8080}") String websiteBaseUrl) {
    this.transportRegistry = Objects.requireNonNull(transportRegistry, "transportRegistry");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.profileService = Objects.requireNonNull(profileService, "profileService");
    this.messageResponseRateLimitService =
        Objects.requireNonNull(messageResponseRateLimitService, "messageResponseRateLimitService");
    this.nativeAppSessionService =
        Objects.requireNonNull(nativeAppSessionService, "nativeAppSessionService");
    this.messageResponseLimitNoticeFactory =
        Objects.requireNonNull(
            messageResponseLimitNoticeFactory, "messageResponseLimitNoticeFactory");
    this.responseCreator = Objects.requireNonNull(responseCreator, "responseCreator");
    this.websiteBaseUrl = Objects.requireNonNull(websiteBaseUrl, "websiteBaseUrl");
    this.workflowResponseGate = new WorkflowResponseGate(conversations);
    this.threadContextRecorder = new ConversationThreadContextRecorder(attachmentInputBuilder);
    CadenceWorkflowLauncher workflowLauncher =
        Objects.requireNonNull(cadenceWorkflowLauncher, "cadenceWorkflowLauncher");
    this.toolActivityRunner =
        new AgentToolActivityRunner(
            this,
            this.objectMapper,
            profileService,
            Objects.requireNonNull(toolRegistry, "toolRegistry"),
            Objects.requireNonNull(agentMetricsService, "agentMetricsService"));
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
            Objects.requireNonNull(termsAgreementValidator, "termsAgreementValidator"),
            Objects.requireNonNull(conversationJournalService, "conversationJournalService"));
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    return incomingMessageHandler.computeConversationState(chatId, message);
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    incomingMessageHandler.handleIncomingMessage(claimNativeAppStartToken(message));
  }

  private IncomingMessage claimNativeAppStartToken(IncomingMessage message) {
    try {
      return nativeAppSessionService.claimStartToken(message);
    } catch (Exception e) {
      log.warn("native_app_start_token_claim_failed", e);
      return message;
    }
  }

  private String termsUrl() {
    String baseUrl = websiteBaseUrl.trim();
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
    if (message == null) {
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
    if (message == null) {
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

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    threadContextRecorder.updateThreadContext(state, message);
  }
}
