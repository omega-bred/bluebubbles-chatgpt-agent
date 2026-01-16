package io.breland.bbagent.server.agent.tools;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.Instant;

public class ToolContext {
  private final BBMessageAgent bbMessageAgent;
  private final IncomingMessage message;

  public ToolContext(BBMessageAgent bbMessageAgent, IncomingMessage message) {
    this.bbMessageAgent = bbMessageAgent;
    this.message = message;
  }

  public IncomingMessage message() {
    return message;
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
    ConversationState state = bbMessageAgent.getConversations().get(message.chatGuid());
    if (state != null) {
      state.addTurn(ConversationTurn.assistant(content, Instant.now()));
    }
  }
}
