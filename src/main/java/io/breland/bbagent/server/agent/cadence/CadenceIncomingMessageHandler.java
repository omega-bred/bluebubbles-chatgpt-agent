package io.breland.bbagent.server.agent.cadence;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import io.breland.bbagent.server.agent.decisions.MessageDecisionService;
import io.breland.bbagent.server.agent.decisions.MessageDecisionService.Assessment;
import io.breland.bbagent.server.agent.memory.ConversationJournalService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import io.breland.bbagent.server.agent.terms.TermsGate;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class CadenceIncomingMessageHandler {
  private final ConversationStateStore conversationStateStore;
  private final AgentProfileService profileService;
  private final MessageTransportRegistry transportRegistry;
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;
  private final AgentMetricsService agentMetricsService;
  private final TermsGate termsGate;
  private final PollNotificationEnricher pollNotificationEnricher;
  private final ConversationJournalService conversationJournalService;
  private final MessageDecisionService messageDecisionService;

  public void handleIncomingMessage(IncomingMessage rawMessage) {
    CadenceMessageWorkflowRequest request = prepareForDispatch(rawMessage, null);
    if (request != null) cadenceWorkflowLauncher.startWorkflow(request);
  }

  public boolean canEnqueue(IncomingMessage message) {
    return isEligibleTransportMessage(message) && !profileService.isProcessingBlocked(message);
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    MessageTransport transport = transportRegistry.resolve(message);
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

  public @Nullable CadenceMessageWorkflowRequest prepareForDispatch(
      IncomingMessage rawMessage, @Nullable Long responseVersion) {
    if (!isEligibleTransportMessage(rawMessage)) {
      log.debug("Dropping message {}", rawMessage);
      return null;
    }
    log.info("Processing Message {}", rawMessage);
    profileService.recordMessageIdentities(rawMessage);
    if (profileService.isProcessingBlocked(rawMessage)) {
      return null;
    }
    recordEligibleMessage(rawMessage);
    if (!shouldInvokeAssistant(rawMessage)) {
      return null;
    }
    IncomingMessage message = pollNotificationEnricher.enrich(rawMessage);
    return conversationStateStore.update(
        message.chatGuid(),
        key -> computeConversationState(key, message),
        state -> prepare(state, message, responseVersion));
  }

  private @Nullable CadenceMessageWorkflowRequest prepare(
      ConversationState state, IncomingMessage message, @Nullable Long responseVersion) {
    if (state.hasSeenIncomingMessage(message)) {
      log.info(
          "Dropping already-seen incoming message chat={} guid={} fingerprint={} ts={} lastSeenGuid={} latestSeenTs={}",
          message.chatGuid(),
          message.messageGuid(),
          message.computeMessageFingerprint(),
          message.timestamp(),
          state.getLastProcessedMessageGuid(),
          state.getLatestProcessedMessageTimestamp());
      return null;
    }
    if (state.isStaleIncomingMessage(message)) {
      log.info(
          "Dropping stale incoming message chat={} guid={} ts={} latestSeenTs={} lastSeenGuid={}",
          message.chatGuid(),
          message.messageGuid(),
          message.timestamp(),
          state.getLatestProcessedMessageTimestamp(),
          state.getLastProcessedMessageGuid());
      return null;
    }
    state.markIncomingMessageSeen(message);
    var accepted = new ArrayList<CadenceMessageWorkflowRequest>(1);
    if (termsGate.handle(
        state,
        message,
        (acceptedState, original) -> {
          DecisionRouting assessed = assess(original, acceptedState).routing();
          DecisionRouting routing = new DecisionRouting(assessed.toolNames(), assessed.feedback());
          accepted.add(prepareResponse(acceptedState, original, routing, responseVersion));
        })) {
      return accepted.isEmpty() ? null : accepted.getFirst();
    }
    // Serialize bounded preflight with incoming turns: a later casual reaction must not replace
    // the workflow marker of an earlier substantive request while its classification is pending.
    Assessment assessment = assess(message, state);
    if (assessment.suppress()) {
      state.recordIncomingTurnIfAbsent(message);
      return null;
    }
    return prepareResponse(state, message, assessment.routing(), responseVersion);
  }

  private Assessment assess(IncomingMessage message, ConversationState state) {
    try {
      Assessment assessment =
          messageDecisionService.assess(
              message, state, profileService.getAssistantResponsiveness(message.chatGuid()));
      return assessment == null ? Assessment.CONTINUE : assessment;
    } catch (RuntimeException e) {
      log.warn("message_decision_unavailable failure_type={}", e.getClass().getSimpleName());
      return Assessment.CONTINUE;
    }
  }

  private CadenceMessageWorkflowRequest prepareResponse(
      ConversationState state,
      IncomingMessage message,
      DecisionRouting routing,
      @Nullable Long responseVersion) {
    state.recordPendingIncomingTurn(message);
    markLatestWorkflow(state, message, routing, responseVersion);
    recordAcceptedMessageMetric(message);
    String workflowId = resolveWorkflowId(message, routing);
    if (responseVersion != null) {
      workflowId +=
          isReactionWorkflow(message, routing) ? ":" + responseVersion : ":turn:" + responseVersion;
    }
    return new CadenceMessageWorkflowRequest(
        new AgentWorkflowContext(
            workflowId,
            message.chatGuid(),
            message.messageGuid(),
            Instant.now(),
            routing,
            responseVersion),
        message,
        null);
  }

  private void recordAcceptedMessageMetric(IncomingMessage message) {
    try {
      agentMetricsService.recordAcceptedMessage(message);
    } catch (RuntimeException e) {
      log.warn("Failed to record message metric for {}", message, e);
    }
  }

  private boolean isEligibleTransportMessage(IncomingMessage message) {
    if (message == null) {
      return false;
    }
    if (Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    if (IncomingMessage.chatGuidOrNull(message) == null) {
      return false;
    }
    if (message.isBlueBubblesTransport()
        && message.service() != null
        && !BBMessageAgent.IMESSAGE_SERVICE.equalsIgnoreCase(message.service())) {
      return false;
    }
    if (message.isSystemMessage()) {
      return false;
    }
    if ((message.text() == null || message.text().isBlank())
        && (message.attachments() == null || message.attachments().isEmpty())) {
      if (message.isBlueBubblesTransport()
          && BlueBubblesPollSupport.isPollBundle(message.balloonBundleId())) {
        return true;
      }
      return false;
    }
    return true;
  }

  private boolean shouldInvokeAssistant(IncomingMessage message) {
    AssistantResponsiveness responsiveness =
        profileService.getAssistantResponsiveness(message.chatGuid());
    if (responsiveness == AssistantResponsiveness.SILENT) {
      return isSilentInvocation(message.text());
    }
    return true;
  }

  private void recordEligibleMessage(IncomingMessage message) {
    try {
      conversationJournalService.recordEligibleMessage(message);
    } catch (RuntimeException e) {
      log.warn("conversation_journal_failed failure_type={}", e.getClass().getSimpleName());
    }
  }

  private static boolean isSilentInvocation(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    return trimmed.regionMatches(true, 0, "Chat", 0, 4);
  }

  private String resolveWorkflowId(IncomingMessage message, DecisionRouting routing) {
    if (message == null) {
      return null;
    }
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid != null) {
      return isReactionWorkflow(message, routing)
          ? AgentWorkflowContext.reactionWorkflowId(chatGuid)
          : chatGuid;
    }
    log.warn("Message did not have a chat guid - this is unexpected");
    return UUID.randomUUID().toString();
  }

  private static boolean isReactionWorkflow(IncomingMessage message, DecisionRouting routing) {
    return routing.responseMode() == DecisionRouting.ResponseMode.REACTION
        || MessageReactionSupport.isTapbackNotification(message.text());
  }

  private static void markLatestWorkflow(
      ConversationState state,
      IncomingMessage message,
      DecisionRouting routing,
      @Nullable Long responseVersion) {
    if (isReactionWorkflow(message, routing)) {
      state.setLatestReactionMessageGuid(message.messageGuid());
      state.setLatestReactionVersion(responseVersion);
    } else {
      state.setLatestWorkflowMessageGuid(message.messageGuid());
      state.setLatestResponseVersion(responseVersion);
      state.setLatestWorkflowRunId(null);
      // A newer substantive message supersedes a reaction response as well as the prior request.
      state.setLatestReactionMessageGuid(null);
      state.setLatestReactionVersion(null);
    }
  }
}
