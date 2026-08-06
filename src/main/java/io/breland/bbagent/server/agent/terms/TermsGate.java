package io.breland.bbagent.server.agent.terms;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

@Slf4j
public final class TermsGate {
  private static final String ACCEPTANCE_REPLY =
      "Before I can help, you need to agree to the Terms of Use: %s\n\n"
          + "Reply YES to confirm that you are at least 18, agree not to use this service for spam, abuse, illegal activity, or harmful content, understand that AI output may be inaccurate, and accept that the service is provided as-is with no refunds, no SLA, and no liability guarantees.";

  private final BBMessageAgent messageAgent;
  private final AgentProfileService profileService;
  private final TermsAgreementValidator agreementValidator;
  private final Supplier<String> termsUrl;
  private final AcceptedMessageProcessor acceptedMessageProcessor;

  public TermsGate(
      BBMessageAgent messageAgent,
      AgentProfileService profileService,
      TermsAgreementValidator agreementValidator,
      Supplier<String> termsUrl,
      AcceptedMessageProcessor acceptedMessageProcessor) {
    this.messageAgent = messageAgent;
    this.profileService = profileService;
    this.agreementValidator = agreementValidator;
    this.termsUrl = termsUrl;
    this.acceptedMessageProcessor = acceptedMessageProcessor;
  }

  public boolean handle(ConversationState state, IncomingMessage message) {
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
    ConversationState.PendingTermsAcceptance pending = findPendingAcceptance(state, message);
    if (shouldValidateAgreement(message, pending)
        && agreementValidator.isHighConfidenceAgreement(message.text())) {
      try {
        profileService.acceptTerms(message);
      } catch (RuntimeException e) {
        log.warn("Failed to accept terms for {}", message, e);
        sendReply(
            message,
            "I couldn't save your Terms agreement just now. Please try replying YES again in a moment.",
            promptReplyTarget(message, pending));
        return true;
      }
      state.clearPendingTermsAcceptance(pending);
      acceptedMessageProcessor.process(state, messageToProcessAfterAccepted(message, pending));
      return true;
    }
    ConversationState.PendingTermsAcceptance promptPending =
        pending != null ? pending : recordPendingAcceptance(state, message);
    sendReply(
        message,
        ACCEPTANCE_REPLY.formatted(termsUrl.get()),
        promptReplyTarget(message, promptPending));
    return true;
  }

  private ConversationState.PendingTermsAcceptance findPendingAcceptance(
      ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return null;
    }
    String threadRootGuid = message.isGroup() ? agreementThreadRootGuid(message) : null;
    if (message.isGroup() && StringUtils.isBlank(threadRootGuid)) {
      return null;
    }
    return state.getPendingTermsAcceptance(message.sender(), threadRootGuid);
  }

  private ConversationState.PendingTermsAcceptance recordPendingAcceptance(
      ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return null;
    }
    String threadRootGuid = message.isGroup() ? promptThreadRootGuid(message) : null;
    return state.recordPendingTermsAcceptance(message, threadRootGuid);
  }

  private boolean shouldValidateAgreement(
      IncomingMessage message, ConversationState.PendingTermsAcceptance pending) {
    if (message == null || StringUtils.isBlank(message.text())) {
      return false;
    }
    if (!message.isGroup()) {
      return pending != null || mightBeAgreement(message.text());
    }
    String threadRootGuid = agreementThreadRootGuid(message);
    return pending != null
        && StringUtils.isNotBlank(threadRootGuid)
        && threadRootGuid.equals(pending.threadRootGuid());
  }

  private static boolean mightBeAgreement(String text) {
    if (StringUtils.isBlank(text)) {
      return false;
    }
    String normalized =
        text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    if (StringUtils.isBlank(normalized)) {
      return false;
    }
    return normalized.equals("y")
        || normalized.equals("yes")
        || normalized.equals("yeah")
        || normalized.equals("yep")
        || normalized.equals("sure")
        || normalized.equals("ok")
        || normalized.equals("okay")
        || normalized.equals("agreed")
        || normalized.equals("affirmative")
        || normalized.contains("i agree")
        || normalized.contains("i accept")
        || normalized.contains("sounds good")
        || normalized.startsWith("yes ")
        || normalized.startsWith("yeah ")
        || normalized.startsWith("yep ")
        || normalized.startsWith("sure ")
        || normalized.startsWith("ok ")
        || normalized.startsWith("okay ");
  }

  private IncomingMessage messageToProcessAfterAccepted(
      IncomingMessage agreementMessage, ConversationState.PendingTermsAcceptance pending) {
    if (pending == null || pending.originalMessage() == null) {
      return agreementMessage;
    }
    IncomingMessage original = pending.originalMessage();
    if (StringUtils.isNotBlank(pending.threadRootGuid())) {
      return original.withThreadOriginatorGuid(pending.threadRootGuid());
    }
    return original;
  }

  private void sendReply(
      IncomingMessage message, String reply, @Nullable String selectedMessageGuid) {
    if (StringUtils.isBlank(selectedMessageGuid)) {
      messageAgent.sendThreadAwareTextUnmetered(message, reply);
      return;
    }
    messageAgent.sendTextUnmetered(
        message, new OutgoingTextMessage(reply, selectedMessageGuid, null, null));
  }

  private String promptReplyTarget(
      IncomingMessage message, ConversationState.PendingTermsAcceptance pending) {
    if (message == null || !message.isGroup()) {
      return null;
    }
    if (pending != null && StringUtils.isNotBlank(pending.threadRootGuid())) {
      return pending.threadRootGuid();
    }
    return promptThreadRootGuid(message);
  }

  private static String promptThreadRootGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    String existingThreadRootGuid = agreementThreadRootGuid(message);
    if (StringUtils.isNotBlank(existingThreadRootGuid)) {
      return existingThreadRootGuid;
    }
    return message.messageGuid();
  }

  private static String agreementThreadRootGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (StringUtils.isNotBlank(message.threadOriginatorGuid())) {
      return message.threadOriginatorGuid();
    }
    if (StringUtils.isNotBlank(message.replyToGuid())) {
      return message.replyToGuid();
    }
    return null;
  }

  @FunctionalInterface
  public interface AcceptedMessageProcessor {
    void process(ConversationState state, IncomingMessage message);
  }
}
