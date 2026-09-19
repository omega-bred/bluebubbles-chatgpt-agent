package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
          bluebubbles, iMessage, false, chat, hello,           false, true
          bluebubbles, iMessage,      , chat, hello,           false, true
          bluebubbles, iMessage, true,  chat, hello,           false, false
          bluebubbles,         , false, chat, hello,           false, true
          bluebubbles, imessage, false, chat, hello,           false, true
          bluebubbles, SMS,      false, chat, hello,           false, false
                     , iMessage, false, chat, hello,           false, true
          '',          iMessage, false, chat, hello,           false, true
          BLUEBUBBLES, iMessage, false, chat, hello,           false, true
          lxmf,                , false, chat, hello,           false, true
          LXMF,        SMS,      false, chat, hello,           false, true
          unsupported, iMessage, false, chat, hello,           false, false
          bluebubbles, iMessage, false,     , hello,           false, false
          bluebubbles, iMessage, false, ' ', hello,           false, false
          bluebubbles, iMessage, false, chat,                , false, false
          bluebubbles, iMessage, false, chat, ' ',             false, false
          bluebubbles, iMessage, false, chat, Loved a message, false, false
          bluebubbles, iMessage, false, chat, hello,           true,  false
          """)
  void checksEligibilityBeforeResolvingAccounts(
      String transport,
      String service,
      Boolean fromMe,
      String chatGuid,
      String text,
      boolean systemMessage,
      boolean eligible) {
    ConversationMemoryStore store = mock(ConversationMemoryStore.class);
    AgentAccountResolver resolver = mock(AgentAccountResolver.class);
    ConversationJournalService journal = new ConversationJournalService(store, resolver, null);
    IncomingMessage message =
        new IncomingMessage(
            transport,
            chatGuid,
            "message",
            null,
            text,
            fromMe,
            service,
            "member@example.com",
            true,
            NOW,
            List.of(),
            systemMessage);

    journal.recordEligibleMessage(message);

    if (eligible) {
      verify(resolver).resolveOrCreate(message);
    } else {
      verifyNoInteractions(resolver);
    }
    verifyNoInteractions(store);
  }

  @Test
  void ignoresNullMessagesBeforeResolvingAccounts() {
    ConversationMemoryStore store = mock(ConversationMemoryStore.class);
    AgentAccountResolver resolver = mock(AgentAccountResolver.class);

    new ConversationJournalService(store, resolver, null).recordEligibleMessage(null);

    verifyNoInteractions(store, resolver);
  }

  @Test
  void enabledGroupJournalsMessageAndPostponesOneDebouncedWorkItem() {
    String accountId = createAccount("group-member@example.com");
    String conversationId =
        store.upsertConversation(
            IncomingMessage.TRANSPORT_BLUEBUBBLES, "iMessage;+;journal-group", true, null, NOW);
    store.enableMemory(conversationId, accountId, NOW.minusSeconds(1));

    journalService.recordEligibleMessage(groupMessage("message-1", "first", NOW));
    journalService.recordEligibleMessage(groupMessage("message-2", "second", NOW.plusSeconds(30)));

    List<ConversationMemoryModels.JournalMessage> messages =
        store.findMessages(conversationId, NOW.minusSeconds(1), NOW.plusSeconds(31));
    assertThat(messages)
        .extracting(ConversationMemoryModels.JournalMessage::messageGuid)
        .containsExactly("message-1", "message-2");
    assertThat(messages)
        .extracting(ConversationMemoryModels.JournalMessage::contentHash)
        .containsExactly(
            "a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e",
            "16367aacb67a4a017c8da8ab95682ccb390863780f7114dda0a0e0c55644c7c4");
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
