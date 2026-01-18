package io.breland.bbagent.server.agent;

import java.util.Optional;

public interface AgentSettingsStore {
  Optional<BBMessageAgent.AssistantResponsiveness> findAssistantResponsiveness(String chatGuid);

  void saveAssistantResponsiveness(String chatGuid, BBMessageAgent.AssistantResponsiveness value);

  void deleteAssistantResponsiveness(String chatGuid);

  Optional<String> findGlobalName(String sender);

  void saveGlobalName(String sender, String name);

  void deleteGlobalName(String sender);
}
