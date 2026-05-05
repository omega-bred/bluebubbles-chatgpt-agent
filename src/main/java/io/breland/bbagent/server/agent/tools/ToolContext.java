package io.breland.bbagent.server.agent.tools;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import org.springframework.util.StringUtils;

public class ToolContext {
  private final BBMessageAgent bbMessageAgent;
  private final IncomingMessage message;
  private final AgentWorkflowContext workflowContext;

  public ToolContext(
      BBMessageAgent bbMessageAgent,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    this.bbMessageAgent = bbMessageAgent;
    this.message = message;
    this.workflowContext = workflowContext;
  }

  public IncomingMessage message() {
    return message;
  }

  public String chatGuid() {
    return clean(message == null ? null : message.chatGuid());
  }

  public String messageGuid() {
    return clean(message == null ? null : message.messageGuid());
  }

  public String sender() {
    return clean(message == null ? null : message.sender());
  }

  public String threadOriginatorGuid() {
    return clean(message == null ? null : message.threadOriginatorGuid());
  }

  public boolean isGroupChat() {
    return message != null && message.isGroup();
  }

  public java.util.Map<String, ConversationState> getConversations() {
    return bbMessageAgent.getConversations();
  }

  public com.fasterxml.jackson.databind.ObjectMapper getMapper() {
    return bbMessageAgent.getObjectMapper();
  }

  public String stringify(Object value, String fallback) {
    var mapper = getMapper();
    if (mapper == null) {
      return fallback;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  public void setAssistantResponsiveness(BBMessageAgent.AssistantResponsiveness responsiveness) {
    bbMessageAgent.setAssistantResponsiveness(message.chatGuid(), responsiveness);
  }

  public void setGlobalNameForSender(String sender, String name) {
    bbMessageAgent.setGlobalNameForSender(sender, name);
  }

  public void removeGlobalNameForSender(String sender) {
    bbMessageAgent.removeGlobalNameForSender(sender);
  }

  public void recordAssistantTurn(String content) {
    bbMessageAgent.recordAssistantTurnForCurrentMessage(message, content, workflowContext);
  }

  public boolean sendText(OutgoingTextMessage outgoingMessage) {
    return bbMessageAgent.sendTextFromTool(message, outgoingMessage);
  }

  public boolean sendReaction(String reaction) {
    return bbMessageAgent.sendReactionFromTool(message, reaction);
  }

  public boolean sendReaction(
      String conversationId, String selectedMessageGuid, String reaction, Integer partIndex) {
    return bbMessageAgent.sendReactionFromTool(
        message, conversationId, selectedMessageGuid, reaction, partIndex);
  }

  public boolean canSendResponses() {
    return bbMessageAgent.canSendResponses(workflowContext);
  }

  public AgentWorkflowContext workflowContext() {
    return workflowContext;
  }

  private static String clean(String value) {
    return StringUtils.hasText(value) ? value : null;
  }
}
