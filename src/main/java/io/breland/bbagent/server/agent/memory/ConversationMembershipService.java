package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationMembershipService {
  private final ConversationMemoryStore store;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final AgentAccountResolver accountResolver;
  private final Clock clock;

  public ConversationMembershipService(
      ConversationMemoryStore store,
      BBHttpClientWrapper bbHttpClientWrapper,
      AgentAccountResolver accountResolver,
      @Nullable Clock clock) {
    this.store = store;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.accountResolver = accountResolver;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public Set<String> refreshGroupMembership(String conversationId) {
    ConversationRecord conversation =
        store
            .findConversation(conversationId)
            .filter(ConversationRecord::group)
            .filter(
                value -> IncomingMessage.TRANSPORT_BLUEBUBBLES.equalsIgnoreCase(value.transport()))
            .orElseThrow(
                () -> new MembershipRefreshException("BlueBubbles group conversation required"));
    Chat chat;
    try {
      chat = bbHttpClientWrapper.getConversationInfo(conversation.externalConversationId());
    } catch (RuntimeException e) {
      throw new MembershipRefreshException("Participant lookup failed", e);
    }
    if (chat == null || chat.getParticipants() == null) {
      throw new MembershipRefreshException("Participant lookup returned no snapshot");
    }

    Set<String> accountIds = new LinkedHashSet<>();
    for (ChatParticipant participant : chat.getParticipants()) {
      String address = participant == null ? null : participant.getAddress();
      if (StringUtils.isBlank(address)) {
        continue;
      }
      String accountId =
          accountResolver
              .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, address)
              .map(resolved -> resolved.account().getAccountId())
              .orElseThrow(
                  () -> new MembershipRefreshException("Participant identity could not resolve"));
      accountIds.add(accountId);
    }
    store.latestSenderAccountId(conversationId).ifPresent(accountIds::add);
    if (accountIds.isEmpty()) {
      throw new MembershipRefreshException("Participant snapshot was empty");
    }
    Instant observedAt = clock.instant();
    store.replaceActiveMemberships(conversationId, accountIds, observedAt);
    return Set.copyOf(accountIds);
  }

  public static class MembershipRefreshException extends RuntimeException {
    public MembershipRefreshException(String message) {
      super(message);
    }

    public MembershipRefreshException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
