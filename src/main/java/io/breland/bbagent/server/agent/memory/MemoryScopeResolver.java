package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class MemoryScopeResolver {
  private final ConversationMemoryStore store;
  private final HindsightMemoryStore memories;
  private final ConversationMembershipService memberships;
  private final boolean groupsEnabled;
  private final AuthorizedMemoryRetrievalService retrieval;
  private final @Nullable ConversationDigestService digests;
  private final @Nullable ProactiveCatchupService catchup;

  public MemoryScopeResolver(
      ConversationMemoryStore store,
      HindsightMemoryStore memories,
      ConversationMembershipService memberships,
      @Value("${bbagent.memory.group.enabled:false}") boolean groupsEnabled,
      AuthorizedMemoryRetrievalService retrieval,
      @Nullable ConversationDigestService digests,
      @Nullable ProactiveCatchupService catchup) {
    this.store = store;
    this.memories = memories;
    this.memberships = memberships;
    this.groupsEnabled = groupsEnabled;
    this.retrieval = retrieval;
    this.digests = digests;
    this.catchup = catchup;
  }

  public Optional<String> primaryScope(ToolContext context) {
    if (context == null || context.message() == null || context.canonicalAccountId().isEmpty())
      return Optional.empty();
    IncomingMessage message = context.message();
    String account = context.canonicalAccountId().orElseThrow();
    if (!message.isGroup()) return Optional.of(memories.personalBank(account));
    if (!groupsEnabled || IncomingMessage.chatGuidOrNull(message) == null) return Optional.empty();
    var conversation =
        store.findEnabledConversationId(message.transportOrDefault(), message.chatGuid());
    if (conversation.isEmpty()) return Optional.empty();
    try {
      var audience = memberships.refreshGroupMembership(conversation.get());
      if (!audience.contains(account)) return Optional.empty();
      return Optional.of(memories.groupBank(conversation.get(), audience));
    } catch (ConversationMembershipService.MembershipRefreshException e) {
      return Optional.empty();
    }
  }

  public String save(String bank, String text, IncomingMessage message) {
    String id = DigestUtils.sha256Hex(bank + "\n" + message.messageGuid() + "\n" + text);
    return memories.save(
        bank, id, null, text, message.timestamp() == null ? Instant.now() : message.timestamp());
  }

  public boolean ownsMemory(String bank, String id) {
    return memories.owns(bank, id);
  }

  public boolean isReadOnlyMemory(String bank, String id) {
    return memories.document(id).filter(d -> d.artifactId() != null).isPresent();
  }

  public boolean update(String bank, String id, String text) {
    return memories.replace(bank, id, text);
  }

  public boolean delete(String bank, String id) {
    return memories.delete(bank, id);
  }

  public Optional<AuthorizedMemoryRetrievalService> authorizedRetrievalService() {
    return Optional.of(retrieval);
  }

  public Optional<ConversationDigestService> conversationDigestService() {
    return Optional.ofNullable(digests);
  }

  public Optional<ProactiveCatchupService> proactiveCatchupService() {
    return Optional.ofNullable(catchup);
  }
}
