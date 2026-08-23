package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationStateStore;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.util.Optional;
import org.springframework.lang.Nullable;

public class ToolContext {
  private final AgentOutboundService outboundService;
  private final ConversationStateStore conversationStateStore;
  private final ObjectMapper objectMapper;
  private final @Nullable AgentProfile profile;
  private final IncomingMessage message;
  private final @Nullable AgentWorkflowContext workflowContext;

  public ToolContext(
      AgentOutboundService outboundService,
      ConversationStateStore conversationStateStore,
      ObjectMapper objectMapper,
      @Nullable AgentProfile profile,
      IncomingMessage message,
      @Nullable AgentWorkflowContext workflowContext) {
    this.outboundService = outboundService;
    this.conversationStateStore = conversationStateStore;
    this.objectMapper = objectMapper;
    this.profile = profile;
    this.message = message;
    this.workflowContext = workflowContext;
  }

  public IncomingMessage message() {
    return message;
  }

  public ConversationState getConversationState(String chatGuid) {
    return conversationStateStore.get(chatGuid);
  }

  public ObjectMapper getMapper() {
    return objectMapper;
  }

  public String accountId() {
    if (profile == null) {
      return message == null ? null : message.sender();
    }
    java.util.Optional<String> accountId = profile.resolveOrCreateAccountId(message);
    return accountId.orElse(message == null ? null : message.sender());
  }

  public Optional<String> canonicalAccountId() {
    if (profile == null || message == null) {
      return Optional.empty();
    }
    Optional<String> accountId = profile.resolveCanonicalAccountId(message);
    if (accountId == null) {
      return Optional.empty();
    }
    return accountId.filter(value -> !value.isBlank());
  }

  public void setAssistantResponsiveness(AssistantResponsiveness responsiveness) {
    if (profile != null) {
      profile.setAssistantResponsiveness(message.chatGuid(), responsiveness);
    }
  }

  public void setGlobalNameForSender(String sender, String name) {
    if (profile != null) {
      profile.setGlobalNameForSender(sender, name);
    }
  }

  public void removeGlobalNameForSender(String sender) {
    if (profile != null) {
      profile.removeGlobalNameForSender(sender);
    }
  }

  public void recordAssistantTurn(String content) {
    outboundService.recordAssistantTurn(message, content, workflowContext);
  }

  public boolean sendText(OutgoingTextMessage outgoingMessage) {
    return outboundService.sendTextFromTool(message, outgoingMessage, workflowContext);
  }

  public boolean sendReaction(
      String conversationId, String selectedMessageGuid, String reaction, Integer partIndex) {
    return outboundService.sendReactionFromTool(
        message, conversationId, selectedMessageGuid, reaction, partIndex, workflowContext);
  }

  public boolean canSendResponses() {
    return outboundService.canSendResponses(workflowContext);
  }

  public boolean consumeMessageResponseQuota() {
    return outboundService.consumeMessageResponseQuota(message, workflowContext);
  }

  public @Nullable AgentWorkflowContext workflowContext() {
    return workflowContext;
  }
}
