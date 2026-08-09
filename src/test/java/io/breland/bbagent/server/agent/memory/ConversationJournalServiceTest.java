package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationJournalServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");

  @Autowired private ConversationJournalService journalService;
  @Autowired private ConversationMemoryStore store;
  @Autowired private AgentAccountResolver accountResolver;

  @Test
  void enabledGroupJournalsMessageAndPostponesOneDebouncedWorkItem() {
    String accountId = createAccount("group-member@example.com");
    String conversationId =
        store.upsertConversation(
            IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;journal-group", true, null, NOW);
    store.enableMemory(conversationId, accountId, NOW.minusSeconds(1));

    journalService.recordEligibleMessage(groupMessage("message-1", "first", NOW));
    journalService.recordEligibleMessage(groupMessage("message-2", "second", NOW.plusSeconds(30)));

    assertThat(store.findMessages(conversationId, NOW.minusSeconds(1), NOW.plusSeconds(31)))
        .extracting(ConversationMemoryModels.JournalMessage::messageGuid)
        .containsExactly("message-1", "message-2");
    assertThat(store.extractionAvailableAt(conversationId)).contains(NOW.plusSeconds(90));
  }

  @Test
  void disabledGroupRegistersMembershipWithoutRetainingText() {
    journalService.recordEligibleMessage(groupMessage("disabled-message", "private", NOW));

    String conversationId =
        store
            .findConversationId(IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;journal-group")
            .orElseThrow();
    assertThat(store.findMessages(conversationId, NOW.minusSeconds(1), NOW.plusSeconds(1)))
        .isEmpty();
    assertThat(store.extractionAvailableAt(conversationId)).isEmpty();
    assertThat(store.activeMembershipAccountIds(conversationId, NOW)).hasSize(1);
  }

  @Test
  void directMessageRegistersReturnRouteWithoutRetainingText() {
    IncomingMessage direct =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;-;direct-route",
            "direct-message",
            null,
            "hello",
            false,
            BBMessageAgent.IMESSAGE_SERVICE,
            "direct@example.com",
            false,
            NOW,
            List.of(),
            false);

    journalService.recordEligibleMessage(direct);

    String conversationId =
        store
            .findConversationId(IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;-;direct-route")
            .orElseThrow();
    assertThat(store.findMessages(conversationId, NOW.minusSeconds(1), NOW.plusSeconds(1)))
        .isEmpty();
    assertThat(store.activeMembershipAccountIds(conversationId, NOW)).hasSize(1);
  }

  @Test
  void ineligibleEventsAreNotRegisteredOrRetained() {
    journalService.recordEligibleMessage(
        groupMessage("from-agent", "assistant text", NOW).withText("assistant text"));
    journalService.recordEligibleMessage(
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;+;system-chat",
            "system-message",
            null,
            "system",
            false,
            BBMessageAgent.IMESSAGE_SERVICE,
            "member@example.com",
            true,
            NOW,
            List.of(),
            true));
    journalService.recordEligibleMessage(groupMessage("reaction", "Loved a message", NOW));
    journalService.recordEligibleMessage(groupMessage("blank", "  ", NOW));

    assertThat(
            store.findConversationId(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;system-chat"))
        .isEmpty();
    assertThat(
            store.findConversationId(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;journal-group"))
        .isEmpty();
  }

  private String createAccount(String identifier) {
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, identifier)
        .orElseThrow()
        .account()
        .getAccountId();
  }

  private IncomingMessage groupMessage(String messageGuid, String text, Instant timestamp) {
    boolean fromAgent = "from-agent".equals(messageGuid);
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;journal-group",
        messageGuid,
        null,
        text,
        fromAgent,
        BBMessageAgent.IMESSAGE_SERVICE,
        "group-member@example.com",
        true,
        timestamp,
        List.of(),
        false);
  }
}
