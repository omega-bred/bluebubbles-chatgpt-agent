package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationMembershipServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");

  @Autowired private ConversationMembershipService membershipService;
  @Autowired private ConversationMemoryStore store;
  @Autowired private AgentAccountResolver accountResolver;
  @MockitoBean private BBHttpClientWrapper bbHttpClientWrapper;

  @Test
  void successfulRefreshOpensNewMembershipsAndClosesAbsentOnes() {
    String senderAccountId = createAccount("sender@example.com");
    String conversationId = groupWithLatestSender(senderAccountId);
    when(bbHttpClientWrapper.getConversationInfo("iMessage;+;membership-group"))
        .thenReturn(chat("alice@example.com", "bob@example.com"))
        .thenReturn(chat("alice@example.com"));

    membershipService.refreshGroupMembership(conversationId);

    assertThat(store.activeMembershipAccountIds(conversationId, Instant.now()))
        .containsExactlyInAnyOrder(
            senderAccountId, createAccount("alice@example.com"), createAccount("bob@example.com"));

    membershipService.refreshGroupMembership(conversationId);

    assertThat(store.activeMembershipAccountIds(conversationId, Instant.now()))
        .containsExactlyInAnyOrder(senderAccountId, createAccount("alice@example.com"));
  }

  @Test
  void failedLookupLeavesExistingAudienceUnchangedAndIsRetryable() {
    String senderAccountId = createAccount("retry-sender@example.com");
    String conversationId = groupWithLatestSender(senderAccountId);
    when(bbHttpClientWrapper.getConversationInfo("iMessage;+;membership-group"))
        .thenThrow(new IllegalStateException("unavailable"));

    assertThatThrownBy(() -> membershipService.refreshGroupMembership(conversationId))
        .isInstanceOf(ConversationMembershipService.MembershipRefreshException.class);

    assertThat(store.activeMembershipAccountIds(conversationId, Instant.now()))
        .containsExactly(senderAccountId);
  }

  private String groupWithLatestSender(String senderAccountId) {
    String conversationId =
        store.upsertConversation(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;+;membership-group",
            true,
            "Membership",
            NOW);
    store.recordMembership(conversationId, senderAccountId, NOW);
    store.recordMessage(
        new JournalMessage(
            "membership-message",
            conversationId,
            senderAccountId,
            "hello",
            NOW,
            false,
            false,
            "membership-hash"));
    return conversationId;
  }

  private Chat chat(String... addresses) {
    return new Chat()
        .guid("iMessage;+;membership-group")
        .participants(
            java.util.Arrays.stream(addresses)
                .map(address -> new ChatParticipant().address(address))
                .toList());
  }

  private String createAccount(String identifier) {
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, identifier)
        .orElseThrow()
        .account()
        .getAccountId();
  }
}
