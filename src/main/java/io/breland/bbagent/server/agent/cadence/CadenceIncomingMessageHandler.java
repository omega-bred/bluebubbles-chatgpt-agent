package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

@Slf4j
public final class CadenceIncomingMessageHandler {
  private final BBMessageAgent messageAgent;
  private final Map<String, ConversationState> conversations;
  private final AgentProfileService profileService;
  private final MessageTransportRegistry transportRegistry;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;
  private final @Nullable AgentMetricsService agentMetricsService;
  private final Supplier<String> termsUrl;

  public CadenceIncomingMessageHandler(
      BBMessageAgent messageAgent,
      Map<String, ConversationState> conversations,
      AgentProfileService profileService,
      MessageTransportRegistry transportRegistry,
      BBHttpClientWrapper bbHttpClientWrapper,
      CadenceWorkflowLauncher cadenceWorkflowLauncher,
      @Nullable AgentMetricsService agentMetricsService,
      Supplier<String> termsUrl) {
    this.messageAgent = messageAgent;
    this.conversations = conversations;
    this.profileService = profileService;
    this.transportRegistry = transportRegistry;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    this.agentMetricsService = agentMetricsService;
    this.termsUrl = termsUrl;
  }

  public void handleIncomingMessage(IncomingMessage rawMessage) {
    PreparedIncomingMessage prepared = prepare(rawMessage);
    if (prepared == null) {
      return;
    }
    startCadenceWorkflow(prepared.state(), prepared.message());
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

  private @Nullable PreparedIncomingMessage prepare(IncomingMessage rawMessage) {
    if (!shouldProcess(rawMessage)) {
      log.debug("Dropping message {}", rawMessage);
      return null;
    }
    log.info("Processing Message {}", rawMessage);
    profileService.recordMessageIdentities(rawMessage);
    if (profileService.isProcessingBlocked(rawMessage)) {
      return null;
    }
    IncomingMessage message = enrichPollNotification(rawMessage);
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
    }
    if (handleTermsGate(state, message)) {
      return null;
    }
    synchronized (state) {
      state.recordPendingIncomingTurn(message);
      state.setLatestWorkflowMessageGuid(message.messageGuid());
      state.setLatestWorkflowRunId(null);
    }
    recordAcceptedMessageMetric(message);
    return new PreparedIncomingMessage(state, message);
  }

  private void startCadenceWorkflow(ConversationState state, IncomingMessage message) {
    AgentWorkflowContext workflowContext =
        new AgentWorkflowContext(
            resolveWorkflowId(message), message.chatGuid(), message.messageGuid(), Instant.now());
    log.info("Responding via cadence workflow");
    WorkflowExecution execution =
        cadenceWorkflowLauncher.startWorkflow(
            new CadenceMessageWorkflowRequest(workflowContext, message, null));
    if (execution != null) {
      synchronized (state) {
        if (Objects.equals(state.getLatestWorkflowMessageGuid(), message.messageGuid())) {
          state.setLatestWorkflowRunId(execution.getRunId());
        }
      }
    }
  }

  private void recordAcceptedMessageMetric(IncomingMessage message) {
    if (agentMetricsService == null) {
      return;
    }
    try {
      agentMetricsService.recordAcceptedMessage(message);
    } catch (RuntimeException e) {
      log.warn("Failed to record message metric for {}", message, e);
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
        && !BBMessageAgent.IMESSAGE_SERVICE.equalsIgnoreCase(message.service())) {
      return false;
    }
    if (MessageReactionSupport.isReactionMessage(message.text())) {
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
    AssistantResponsiveness responsiveness =
        profileService.getAssistantResponsiveness(message.chatGuid());
    if (responsiveness == AssistantResponsiveness.SILENT) {
      return isSilentInvocation(message.text());
    }
    return true;
  }

  private IncomingMessage enrichPollNotification(IncomingMessage message) {
    if (message == null
        || !message.isBlueBubblesTransport()
        || !BlueBubblesPollSupport.isPollBundle(message.balloonBundleId())) {
      return message;
    }
    String pollMessageGuid = BlueBubblesPollSupport.pollMessageGuid(message);
    if (pollMessageGuid == null || pollMessageGuid.isBlank()) {
      return message.withText(BlueBubblesPollSupport.fallbackPollNotification(message, null));
    }
    try {
      JsonNode poll = bbHttpClientWrapper.readPollJson(pollMessageGuid);
      return message.withText(
          BlueBubblesPollSupport.formatPollNotification(message, pollMessageGuid, poll));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to read poll update for triggerGuid={} pollGuid={}",
          message.messageGuid(),
          pollMessageGuid,
          e);
      return message.withText(
          BlueBubblesPollSupport.fallbackPollNotification(message, pollMessageGuid));
    }
  }

  private boolean handleTermsGate(ConversationState state, IncomingMessage message) {
    if (message == null) {
      return false;
    }
    AgentAccountResolver.ResolvedAccount resolved;
    try {
      Optional<AgentAccountResolver.ResolvedAccount> resolvedAccount =
          profileService.resolveOrCreateAccount(message);
      if (resolvedAccount.isEmpty()) {
        return false;
      }
      resolved = resolvedAccount.get();
    } catch (RuntimeException e) {
      log.warn("Failed to resolve account for terms gate {}", message, e);
      return false;
    }
    if (resolved.account().getTermsAcceptedAt() != null) {
      return false;
    }
    String reply;
    if (isTermsAgreementText(message.text())) {
      try {
        profileService.acceptTerms(message);
      } catch (RuntimeException e) {
        log.warn("Failed to accept terms for {}", message, e);
        reply =
            "I couldn't save your Terms agreement just now. Please try replying YES again in a moment.";
        sendAndRecordTermsGateReply(state, message, reply);
        return true;
      }
      reply = BBMessageAgent.TERMS_ACCEPTED_REPLY;
    } else {
      reply = BBMessageAgent.TERMS_ACCEPTANCE_REPLY.formatted(termsUrl.get());
    }
    sendAndRecordTermsGateReply(state, message, reply);
    return true;
  }

  private void sendAndRecordTermsGateReply(
      ConversationState state, IncomingMessage message, String reply) {
    if (messageAgent.sendThreadAwareTextUnmetered(message, reply) && state != null) {
      messageAgent.recordAssistantTurnForCurrentMessage(message, reply, null);
      messageAgent.updateThreadContext(state, message);
    }
  }

  private static boolean isTermsAgreementText(String text) {
    if (text == null) {
      return false;
    }
    String normalized =
        text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    return Set.of("yes", "y", "agree", "i agree").contains(normalized);
  }

  private static boolean isSilentInvocation(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.stripLeading();
    return trimmed.regionMatches(true, 0, "Chat", 0, 4);
  }

  private String resolveWorkflowId(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      return message.chatGuid();
    }
    log.warn("Message did not have a chat guid - this is unexpected");
    return UUID.randomUUID().toString();
  }

  private record PreparedIncomingMessage(ConversationState state, IncomingMessage message) {}
}
