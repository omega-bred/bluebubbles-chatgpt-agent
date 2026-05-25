package io.breland.bbagent.server.agent.tools;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import org.springframework.lang.Nullable;

public class ToolContext {
  private final BBMessageAgent bbMessageAgent;
  private final @Nullable AgentProfile profile;
  private final IncomingMessage message;
  private final AgentWorkflowContext workflowContext;

  public ToolContext(
      BBMessageAgent bbMessageAgent,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    this(bbMessageAgent, null, message, workflowContext);
  }

  public ToolContext(
      BBMessageAgent bbMessageAgent,
      @Nullable AgentProfile profile,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    this.bbMessageAgent = bbMessageAgent;
    this.profile = profile;
    this.message = message;
    this.workflowContext = workflowContext;
  }

  public IncomingMessage message() {
    return message;
  }

  public java.util.Map<String, ConversationState> getConversations() {
    return bbMessageAgent.getConversations();
  }

  public com.fasterxml.jackson.databind.ObjectMapper getMapper() {
    return bbMessageAgent.getObjectMapper();
  }

  public String accountId() {
    if (profile == null) {
      return message == null ? null : message.sender();
    }
    java.util.Optional<String> accountId = profile.resolveOrCreateAccountId(message);
    return accountId.orElse(message == null ? null : message.sender());
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
    bbMessageAgent.recordAssistantTurnForCurrentMessage(message, content, workflowContext);
  }

  public boolean sendText(OutgoingTextMessage outgoingMessage) {
    return bbMessageAgent.sendTextFromTool(message, outgoingMessage, workflowContext);
  }

  public boolean sendReaction(
      String conversationId, String selectedMessageGuid, String reaction, Integer partIndex) {
    return bbMessageAgent.sendReactionFromTool(
        message, conversationId, selectedMessageGuid, reaction, partIndex, workflowContext);
  }

  public boolean canSendResponses() {
    return bbMessageAgent.canSendResponses(workflowContext);
  }

  public boolean consumeMessageResponseQuota() {
    return bbMessageAgent.consumeMessageResponseQuota(message, workflowContext);
  }

  public AgentWorkflowContext workflowContext() {
    return workflowContext;
  }
}
