package io.breland.bbagent.server.agent;

import com.uber.cadence.activity.Activity;
import io.breland.bbagent.server.agent.OutboundDeliveryStore.Outcome;
import io.breland.bbagent.server.agent.OutboundDeliveryStore.Result;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper.AttachmentData;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitDecision;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
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
  private final OutboundDeliveryStore deliveries;

  public boolean usesSharedDeliveryStore() {
    return deliveries != null;
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    return workflowResponseGate.canSendResponses(workflowContext);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    return workflowResponseGate.canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  public void recordAssistantTurn(
      IncomingMessage message, String content, AgentWorkflowContext workflowContext) {
    content = AssistantMessageText.stripLeadingMessageGuid(content);
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid == null || content == null || content.isBlank()) {
      return;
    }
    if (!canSendResponses(workflowContext)) {
      return;
    }
    String recordedContent = content;
    conversationStateStore.updateIfPresent(
        chatGuid,
        state -> {
          if (!canSendResponses(workflowContext)) return;
          if (deliveries != null
              && !state.rememberAssistantDelivery(
                  deliveryId(message, workflowContext, "history", recordedContent))) return;
          recordIncomingTurnsForResponse(state, message);
          state.addTurn(ConversationTurn.assistant(recordedContent, Instant.now()));
        });
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
    return sendThreadAwareText(message, text, workflowContext, true);
  }

  public boolean sendThreadAwareTextUnmetered(IncomingMessage message, String text) {
    return sendThreadAwareText(message, text, null, false);
  }

  private boolean sendThreadAwareText(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext, boolean metered) {
    if (message == null || text == null || text.isBlank()) {
      return false;
    }
    MessageTransport transport = transportRegistry.resolve(message);
    String replyTarget =
        transport.supportsThreadReplies() ? ThreadReplySupport.threadRootGuid(message) : null;
    return sendText(
            message,
            new OutgoingTextMessage(text, replyTarget, null, null),
            workflowContext,
            metered)
        .delivered();
  }

  public boolean sendTextUnmetered(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    return sendText(message, outgoingMessage, null, false).delivered();
  }

  private Result sendText(
      IncomingMessage message,
      OutgoingTextMessage outgoingMessage,
      AgentWorkflowContext workflowContext,
      boolean metered) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return new Result(Outcome.DECLINED, null);
    }
    String text = AssistantMessageText.stripLeadingMessageGuid(outgoingMessage.text());
    if (text.isBlank()) {
      return new Result(Outcome.DECLINED, null);
    }
    MessageTransport transport = transportRegistry.resolve(message);
    var normalized =
        new OutgoingTextMessage(
            text,
            outgoingMessage.selectedMessageGuid(),
            outgoingMessage.effectId(),
            outgoingMessage.partIndex());
    return deliver(
        message,
        workflowContext,
        "text",
        normalized,
        metered,
        id -> transport.sendText(message, normalized.withDeliveryId(id)) ? "sent" : null,
        (id, startedAt) ->
            transport.confirmText(message, normalized.withDeliveryId(id), startedAt)
                ? "sent"
                : null);
  }

  public boolean sendTextFromTool(
      IncomingMessage message,
      OutgoingTextMessage outgoingMessage,
      AgentWorkflowContext workflowContext) {
    return sendText(message, outgoingMessage, workflowContext, true).delivered();
  }

  public boolean sendReactionFromTool(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex,
      AgentWorkflowContext workflowContext) {
    MessageTransport transport = reactionTransport(message, reaction);
    if (transport == null) {
      return false;
    }
    return deliver(
            message,
            workflowContext,
            "reaction",
            Arrays.asList(conversationId, selectedMessageGuid, reaction, partIndex),
            true,
            id ->
                transport.sendReaction(
                        message, conversationId, selectedMessageGuid, reaction, partIndex)
                    ? "sent"
                    : null,
            null)
        .delivered();
  }

  public boolean sendReaction(
      IncomingMessage message, String reaction, AgentWorkflowContext context) {
    MessageTransport transport = reactionTransport(message, reaction);
    return transport != null
        && deliver(
                message,
                context,
                "reaction",
                reaction,
                true,
                id -> transport.sendReaction(message, reaction) ? "sent" : null,
                null)
            .delivered();
  }

  public Result sendImages(
      IncomingMessage message,
      AgentWorkflowContext context,
      String caption,
      List<AttachmentData> attachments) {
    MessageTransport transport = transportRegistry.resolve(message);
    List<String> digests =
        attachments.stream().map(attachment -> DigestUtils.sha256Hex(attachment.bytes())).toList();
    return deliver(
        message,
        context,
        "images",
        Arrays.asList(caption, digests),
        true,
        id ->
            transport.sendMultipartMessage(message.chatGuid(), caption, attachments)
                ? "sent"
                : null,
        null);
  }

  public Result sendPoll(
      IncomingMessage message, AgentWorkflowContext context, Function<String, String> send) {
    return deliver(message, context, "poll", null, true, send, null);
  }

  public Result runToolOnce(
      IncomingMessage message, AgentWorkflowContext context, String callId, Supplier<String> run) {
    return deliver(message, context, "tool-result", callId, false, id -> run.get(), null);
  }

  private Result deliver(
      IncomingMessage message,
      AgentWorkflowContext context,
      String kind,
      Object signature,
      boolean metered,
      Function<String, String> send,
      BiFunction<String, Instant, String> reconcile) {
    if (deliveries == null) {
      if (!canSendResponses(context)
          || (metered && !consumeMessageResponseQuota(message, context))) {
        return new Result(Outcome.DECLINED, null);
      }
      String receipt = send.apply(null);
      return new Result(receipt == null ? Outcome.UNCERTAIN : Outcome.DELIVERED, receipt);
    }
    return deliveries.deliver(
        deliveryId(message, context, kind, signature),
        () -> workflowResponseGate.canClaimDelivery(context),
        () ->
            canSendResponses(context)
                && (!metered || consumeMessageResponseQuota(message, context)),
        send,
        reconcile);
  }

  private String deliveryId(
      IncomingMessage message, AgentWorkflowContext context, String kind, Object signature) {
    String runId = null;
    if (context != null && ScheduledEventTool.isScheduledWorkflowId(context.workflowId())) {
      runId = Activity.getWorkflowExecution().getRunId();
    }
    String messageId =
        message.messageGuid() != null ? message.messageGuid() : message.computeMessageFingerprint();
    return deliveries.id(
        message.transportOrDefault(), message.chatGuid(), messageId, runId, kind, signature);
  }

  private @Nullable MessageTransport reactionTransport(IncomingMessage message, String reaction) {
    if (message == null || reaction == null || reaction.isBlank()) {
      return null;
    }
    MessageTransport transport = transportRegistry.resolve(message);
    if (!transport.supportsReactions()) {
      return null;
    }
    return transport;
  }

  public boolean notifyIfMessageResponseLimitExceeded(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (message == null || profileService.isCanaryAccount(message)) {
      return false;
    }
    try {
      MessageResponseRateLimitService.MessageResponseLimitStatus status =
          messageResponseRateLimitService.statusFor(message);
      if (!status.tracked() || status.rateLimit() == null || !status.rateLimit().exhausted()) {
        return false;
      }
      if (!io.breland.bbagent.server.agent.reactions.MessageReactionSupport.isTapbackNotification(
          message.text())) {
        sendRateLimitExceededNotice(message, status, workflowContext);
      }
      return true;
    } catch (RuntimeException e) {
      log.warn("Failed to check message response rate limit for {}", message, e);
      return false;
    }
  }

  public boolean consumeMessageResponseQuota(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (profileService.isCanaryAccount(message)) {
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
    if (sendThreadAwareText(message, text, workflowContext, false)) {
      recordAssistantTurn(message, text, workflowContext);
    }
  }
}
