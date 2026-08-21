package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public final class AgentOutboundService {
  private final ConversationStateStore conversationStateStore;
  private final MessageTransportRegistry transportRegistry;
  private final AgentProfileService profileService;
  private final WorkflowResponseGate workflowResponseGate;
  private final MessageResponseRateLimitService messageResponseRateLimitService;
  private final MessageResponseLimitNoticeFactory messageResponseLimitNoticeFactory;

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    return workflowResponseGate.canSendResponses(workflowContext);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    return workflowResponseGate.canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  public void recordAssistantTurn(
      IncomingMessage message, String content, AgentWorkflowContext workflowContext) {
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid == null || content == null || content.isBlank()) {
      return;
    }
    if (!canSendResponses(workflowContext)) {
      return;
    }
    ConversationState state = conversationStateStore.get(chatGuid);
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
    if (message == null || isCanaryAccount(message)) {
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
      recordAssistantTurn(message, text, workflowContext);
    }
  }

  private boolean isCanaryAccount(IncomingMessage message) {
    return message != null && profileService.isCanaryAccount(message);
  }
}
