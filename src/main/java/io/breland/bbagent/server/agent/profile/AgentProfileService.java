package io.breland.bbagent.server.agent.profile;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public final class AgentProfileService implements AgentProfile {
  private final AgentSettingsStore agentSettingsStore;
  private final @Nullable AgentAccountResolver accountResolver;

  public AgentProfileService(
      AgentSettingsStore agentSettingsStore, @Nullable AgentAccountResolver accountResolver) {
    this.agentSettingsStore = agentSettingsStore;
    this.accountResolver = accountResolver;
  }

  public AssistantResponsiveness getAssistantResponsiveness(String chatGuid) {
    if (StringUtils.isBlank(chatGuid)) {
      return AssistantResponsiveness.DEFAULT;
    }
    return agentSettingsStore
        .findAssistantResponsiveness(chatGuid)
        .orElse(AssistantResponsiveness.DEFAULT);
  }

  @Override
  public void setAssistantResponsiveness(String chatGuid, AssistantResponsiveness responsiveness) {
    if (StringUtils.isBlank(chatGuid) || responsiveness == null) {
      return;
    }
    if (responsiveness == AssistantResponsiveness.DEFAULT) {
      agentSettingsStore.deleteAssistantResponsiveness(chatGuid);
      return;
    }
    agentSettingsStore.saveAssistantResponsiveness(chatGuid, responsiveness);
  }

  public String getGlobalNameForMessage(IncomingMessage message) {
    return resolveOrCreateAccountId(message)
        .flatMap(agentSettingsStore::findGlobalName)
        .orElse(null);
  }

  @Override
  public void setGlobalNameForSender(String sender, String name) {
    if (StringUtils.isBlank(name)) {
      return;
    }
    resolveOrCreateSenderAccountId(sender)
        .ifPresent(accountId -> agentSettingsStore.saveGlobalName(accountId, name));
  }

  @Override
  public void removeGlobalNameForSender(String sender) {
    resolveOrCreateSenderAccountId(sender).ifPresent(agentSettingsStore::deleteGlobalName);
  }

  @Override
  public Optional<String> resolveOrCreateAccountId(IncomingMessage message) {
    if (message == null) {
      return Optional.empty();
    }
    if (accountResolver == null) {
      return Optional.ofNullable(StringUtils.defaultIfBlank(message.sender(), null));
    }
    return resolveOrCreateAccount(message).map(resolved -> resolved.account().getAccountId());
  }

  public Optional<AgentAccountResolver.ResolvedAccount> resolveOrCreateAccount(
      IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return Optional.empty();
    }
    return accountResolver.resolveOrCreate(message);
  }

  public Optional<AgentAccountResolver.ResolvedAccount> resolveAccount(IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return Optional.empty();
    }
    return accountResolver.resolve(message);
  }

  public void acceptTerms(IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return;
    }
    accountResolver.acceptTerms(message);
  }

  public boolean isProcessingBlocked(IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return false;
    }
    try {
      return resolveAccount(message)
          .map(AgentAccountResolver.ResolvedAccount::account)
          .filter(AgentAccountEntity::isProcessingBlocked)
          .map(
              account -> {
                log.warn(
                    "Dropping message for blocked account account_id={} chat={} message_guid={} reason={}",
                    account.getAccountId(),
                    message.chatGuid(),
                    message.messageGuid(),
                    account.getProcessingBlockedReason());
                return true;
              })
          .orElse(false);
    } catch (RuntimeException e) {
      log.warn("Failed to check account block status for {}", message, e);
      return false;
    }
  }

  public void recordMessageIdentities(IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return;
    }
    try {
      accountResolver.recordMessageIdentities(message);
    } catch (Exception e) {
      log.debug("Failed to record account identities", e);
    }
  }

  public List<AgentAccountIdentityEntity> resolveAccountIdentities(IncomingMessage message) {
    if (accountResolver == null || message == null) {
      return List.of();
    }
    return resolveAccount(message)
        .map(resolved -> accountResolver.identitiesForAccount(resolved.account().getAccountId()))
        .orElseGet(List::of);
  }

  private Optional<String> resolveOrCreateSenderAccountId(String sender) {
    if (StringUtils.isBlank(sender)) {
      return Optional.empty();
    }
    if (accountResolver == null) {
      return Optional.of(sender);
    }
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, sender)
        .map(resolved -> resolved.account().getAccountId());
  }
}
