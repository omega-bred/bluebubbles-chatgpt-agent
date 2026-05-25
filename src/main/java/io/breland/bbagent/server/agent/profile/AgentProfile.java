package io.breland.bbagent.server.agent.profile;

import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.Optional;

public interface AgentProfile {
  Optional<String> resolveOrCreateAccountId(IncomingMessage message);

  void setAssistantResponsiveness(String chatGuid, AssistantResponsiveness responsiveness);

  void setGlobalNameForSender(String sender, String name);

  void removeGlobalNameForSender(String sender);
}
