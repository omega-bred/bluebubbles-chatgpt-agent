package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.isEligibleConversationText;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConversationJournalService {
  private final ConversationMemoryStore store;
  private final AgentAccountResolver accountResolver;
  private final Duration debounce;
  private final boolean globallyEnabled;

  @Autowired
  public ConversationJournalService(
      ConversationMemoryStore store,
      AgentAccountResolver accountResolver,
      @Value("${bbagent.memory.group.debounce:PT60S}") Duration debounce,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this.store = store;
    this.accountResolver = accountResolver;
    this.debounce = debounce == null ? Duration.ofSeconds(60) : debounce;
    this.globallyEnabled = globallyEnabled;
  }

  ConversationJournalService(
      ConversationMemoryStore store, AgentAccountResolver accountResolver, Duration debounce) {
    this(store, accountResolver, debounce, true);
  }

  public void recordEligibleMessage(IncomingMessage message) {
    if (!isEligibleConversationText(message) || Boolean.TRUE.equals(message.fromMe())) {
      return;
    }
    String accountId =
        accountResolver
            .resolveOrCreate(message)
            .map(resolved -> resolved.account().getAccountId())
            .orElse(null);
    if (StringUtils.isBlank(accountId)) {
      return;
    }
    Instant observedAt = message.timestamp() == null ? Instant.now() : message.timestamp();
    String conversationId =
        store.upsertConversation(
            message.transportOrDefault(), message.chatGuid(), message.isGroup(), null, observedAt);
    store.recordMembership(conversationId, accountId, observedAt);
    if (!globallyEnabled
        || !message.isGroup()
        || store
            .findEnabledConversationId(message.transportOrDefault(), message.chatGuid())
            .isEmpty()
        || StringUtils.isBlank(message.messageGuid())) {
      return;
    }
    String text = message.text().trim();
    store.recordMessage(
        new JournalMessage(
            message.messageGuid(),
            conversationId,
            accountId,
            text,
            observedAt,
            false,
            false,
            DigestUtils.sha256Hex(text)));
    store.scheduleExtraction(conversationId, observedAt.plus(debounce));
  }
}
