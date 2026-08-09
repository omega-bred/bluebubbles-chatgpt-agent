package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.reactions.MessageReactionSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
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

  public boolean recordEligibleMessage(IncomingMessage message) {
    if (!isEligible(message)) {
      return false;
    }
    String accountId =
        accountResolver
            .resolveOrCreate(message)
            .map(resolved -> resolved.account().getAccountId())
            .orElse(null);
    if (StringUtils.isBlank(accountId)) {
      return false;
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
      return true;
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
            sha256(text)));
    store.scheduleExtraction(conversationId, observedAt.plus(debounce));
    return true;
  }

  private boolean isEligible(IncomingMessage message) {
    if (message == null
        || Boolean.TRUE.equals(message.fromMe())
        || message.isSystemMessage()
        || StringUtils.isBlank(IncomingMessage.chatGuidOrNull(message))
        || StringUtils.isBlank(message.text())
        || MessageReactionSupport.isReactionMessage(message.text())) {
      return false;
    }
    if (message.isBlueBubblesTransport()) {
      return message.service() == null
          || BBMessageAgent.IMESSAGE_SERVICE.equalsIgnoreCase(message.service());
    }
    return message.isLxmfTransport();
  }

  private String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
