package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MemoryScopeResolver {
  private final ConversationMemoryStore store;
  private final boolean legacyScopeReadEnabled;

  public MemoryScopeResolver(
      ConversationMemoryStore store,
      @Value("${bbagent.memory.legacy-scope-read-enabled:true}") boolean legacyScopeReadEnabled) {
    this.store = store;
    this.legacyScopeReadEnabled = legacyScopeReadEnabled;
  }

  public Optional<String> primaryScope(ToolContext context) {
    if (context == null || context.message() == null) {
      return Optional.empty();
    }
    IncomingMessage message = context.message();
    if (message.isGroup()) {
      String chatGuid = IncomingMessage.chatGuidOrNull(message);
      if (chatGuid == null) {
        return Optional.empty();
      }
      return store
          .findEnabledConversationId(message.transportOrDefault(), chatGuid)
          .map(conversationId -> "conversation:" + conversationId);
    }
    return context.canonicalAccountId().map(accountId -> "account:" + accountId);
  }

  public Optional<String> legacyReadScope(ToolContext context) {
    if (!legacyScopeReadEnabled || context == null || context.message() == null) {
      return Optional.empty();
    }
    IncomingMessage message = context.message();
    if (message.isGroup()) {
      return Optional.ofNullable(IncomingMessage.chatGuidOrNull(message));
    }
    return Optional.ofNullable(StringUtils.trimToNull(message.sender()));
  }

  public void recordOwnership(String canonicalScope, String memoryId, String text) {
    store.recordCanonicalMemory(canonicalScope, memoryId, hash(text), Instant.now());
  }

  public boolean ownsMemory(String canonicalScope, String memoryId) {
    return store.ownsCanonicalMemory(canonicalScope, memoryId);
  }

  public void updateOwnership(String canonicalScope, String memoryId, String text) {
    store.updateCanonicalMemory(canonicalScope, memoryId, hash(text), Instant.now());
  }

  public void removeOwnership(String canonicalScope, String memoryId) {
    store.deleteCanonicalMemory(canonicalScope, memoryId);
  }

  private String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
