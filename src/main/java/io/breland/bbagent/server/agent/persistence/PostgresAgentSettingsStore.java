package io.breland.bbagent.server.agent.persistence;

import io.breland.bbagent.server.agent.AgentSettingsStore;
import io.breland.bbagent.server.agent.BBMessageAgent;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class PostgresAgentSettingsStore implements AgentSettingsStore {
  private final AssistantResponsivenessRepository responsivenessRepository;
  private final GlobalContactRepository globalContactRepository;

  public PostgresAgentSettingsStore(
      AssistantResponsivenessRepository responsivenessRepository,
      GlobalContactRepository globalContactRepository) {
    this.responsivenessRepository = responsivenessRepository;
    this.globalContactRepository = globalContactRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BBMessageAgent.AssistantResponsiveness> findAssistantResponsiveness(
      String chatGuid) {
    return responsivenessRepository
        .findById(chatGuid)
        .flatMap(
            entity -> {
              try {
                return Optional.of(
                    BBMessageAgent.AssistantResponsiveness.valueOf(entity.getResponsiveness()));
              } catch (IllegalArgumentException ex) {
                log.warn(
                    "Unknown responsiveness value {} for chat {}",
                    entity.getResponsiveness(),
                    chatGuid);
                return Optional.empty();
              }
            });
  }

  @Override
  public void saveAssistantResponsiveness(
      String chatGuid, BBMessageAgent.AssistantResponsiveness value) {
    if (chatGuid == null || chatGuid.isBlank() || value == null) {
      return;
    }
    responsivenessRepository.save(new AssistantResponsivenessEntity(chatGuid, value.name()));
  }

  @Override
  public void deleteAssistantResponsiveness(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return;
    }
    responsivenessRepository.deleteById(chatGuid);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findGlobalName(String sender) {
    return globalContactRepository.findById(sender).map(GlobalContactEntity::getName);
  }

  @Override
  public void saveGlobalName(String sender, String name) {
    if (sender == null || sender.isBlank() || name == null || name.isBlank()) {
      return;
    }
    globalContactRepository.save(new GlobalContactEntity(sender, name.trim()));
  }

  @Override
  public void deleteGlobalName(String sender) {
    if (sender == null || sender.isBlank()) {
      return;
    }
    globalContactRepository.deleteById(sender);
  }
}
