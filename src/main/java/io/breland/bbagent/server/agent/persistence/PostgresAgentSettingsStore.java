package io.breland.bbagent.server.agent.persistence;

import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.profile.AgentSettingsStore;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class PostgresAgentSettingsStore implements AgentSettingsStore {
  private final AssistantResponsivenessRepository responsivenessRepository;
  private final AgentAccountRepository accountRepository;

  public PostgresAgentSettingsStore(
      AssistantResponsivenessRepository responsivenessRepository,
      AgentAccountRepository accountRepository) {
    this.responsivenessRepository = responsivenessRepository;
    this.accountRepository = accountRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AssistantResponsiveness> findAssistantResponsiveness(String chatGuid) {
    return responsivenessRepository
        .findById(chatGuid)
        .flatMap(
            entity -> {
              try {
                return Optional.of(AssistantResponsiveness.valueOf(entity.getResponsiveness()));
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
  public void saveAssistantResponsiveness(String chatGuid, AssistantResponsiveness value) {
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
  public Optional<String> findGlobalName(String accountId) {
    return accountRepository.findById(accountId).map(AgentAccountEntity::getGlobalContactName);
  }

  @Override
  public void saveGlobalName(String accountId, String name) {
    if (accountId == null || accountId.isBlank() || name == null || name.isBlank()) {
      return;
    }
    accountRepository
        .findById(accountId)
        .ifPresent(
            account -> {
              account.setGlobalContactName(name.trim());
              account.setUpdatedAt(java.time.Instant.now());
              accountRepository.save(account);
            });
  }

  @Override
  public void deleteGlobalName(String accountId) {
    if (accountId == null || accountId.isBlank()) {
      return;
    }
    accountRepository
        .findById(accountId)
        .ifPresent(
            account -> {
              account.setGlobalContactName(null);
              account.setUpdatedAt(java.time.Instant.now());
              accountRepository.save(account);
            });
  }
}
