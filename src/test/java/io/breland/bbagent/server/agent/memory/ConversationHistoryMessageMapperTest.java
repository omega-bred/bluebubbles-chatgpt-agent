package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationHistoryMessageMapperTest {
  private final AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
  private final ConversationHistoryMessageMapper mapper =
      new ConversationHistoryMessageMapper(accountResolver);

  @Test
  void labelsRequestingAccountAsYou() {
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved("account-1", null)));

    QuestionMessage mapped =
        mapper.fromBlueBubbles(rawMessage("+15555550199"), "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("you");
  }

  @Test
  void labelsKnownParticipantWithoutCreatingAccount() {
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved("account-2", "Dom")));

    QuestionMessage mapped =
        mapper.fromBlueBubbles(rawMessage("+15555550199"), "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("Dom");
    verify(accountResolver, never()).resolveOrCreate(any(IncomingMessage.class));
  }

  @Test
  void fallsBackToMaskedIdentityForAnOverlongGlobalContactNameWithoutMutatingIt() {
    String overlongName = "D".repeat(161);
    AgentAccountEntity account = account("account-2", overlongName);
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(new ResolvedAccount(account, List.of())));

    QuestionMessage mapped =
        mapper.fromBlueBubbles(rawMessage("+15555550199"), "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("participant ending 0199");
    assertThat(account.getGlobalContactName()).isEqualTo(overlongName);
    verify(accountResolver, never()).resolveOrCreate(any(IncomingMessage.class));
  }

  @Test
  void fallsBackToUnknownForAGlobalContactNameWithMoreThanEightTokens() {
    String unsafeName = "one two three four five six seven eight nine";
    AgentAccountEntity account = account("account-2", unsafeName);
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(new ResolvedAccount(account, List.of())));
    JournalMessage message =
        new JournalMessage(
            "journal-1",
            "conversation-1",
            "account-2",
            "We chose Saturday",
            Instant.parse("2026-08-09T10:00:00Z"),
            false,
            false,
            "hash");

    QuestionMessage mapped = mapper.fromJournal(message, "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("unknown participant");
    assertThat(account.getGlobalContactName()).isEqualTo(unsafeName);
  }

  @Test
  void fallsBackToUnknownForAPunctuationDelimitedGlobalContactNameWithMoreThanEightTokens() {
    String unsafeName = "A!B!C!D!E!F!G!H!I";
    AgentAccountEntity account = account("account-2", unsafeName);
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(new ResolvedAccount(account, List.of())));
    JournalMessage message =
        new JournalMessage(
            "journal-1",
            "conversation-1",
            "account-2",
            "We chose Saturday",
            Instant.parse("2026-08-09T10:00:00Z"),
            false,
            false,
            "hash");

    QuestionMessage mapped = mapper.fromJournal(message, "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("unknown participant");
    assertThat(account.getGlobalContactName()).isEqualTo(unsafeName);
  }

  @Test
  void masksUnknownIdentityAndRejectsIneligibleEvents() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());

    assertThat(
            mapper
                .fromBlueBubbles(rawMessage("+1 (555) 555-0199"), "account-1")
                .orElseThrow()
                .participant())
        .isEqualTo("participant ending 0199");
    assertThat(mapper.fromBlueBubbles(reactionMessage(), "account-1")).isEmpty();
    assertThat(mapper.fromBlueBubbles(fromMeMessage(), "account-1")).isEmpty();
    assertThat(mapper.fromBlueBubbles(systemMessage(), "account-1")).isEmpty();
    assertThat(mapper.fromBlueBubbles(serviceMessage(), "account-1")).isEmpty();
    assertThat(mapper.fromBlueBubbles(blankMessage(), "account-1")).isEmpty();
  }

  @Test
  void labelsUnusableIdentityAsUnknownParticipant() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());

    assertThat(mapper.fromBlueBubbles(rawMessage(null), "account-1").orElseThrow().participant())
        .isEqualTo("unknown participant");
  }

  @Test
  void mapsEligibleJournalMessagesWithoutCreatingOrMergingAccounts() {
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(resolved("account-2", "Dom")));
    JournalMessage message =
        new JournalMessage(
            "journal-1",
            "conversation-1",
            "account-2",
            "  We chose Saturday  ",
            Instant.parse("2026-08-09T10:00:00Z"),
            false,
            false,
            "hash");

    QuestionMessage mapped = mapper.fromJournal(message, "account-1").orElseThrow();

    assertThat(mapped.participant()).isEqualTo("Dom");
    assertThat(mapped.text()).isEqualTo("We chose Saturday");
    verify(accountResolver, never()).resolveOrCreate(any(IncomingMessage.class));
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner rawMessage(String sender) {
    return message("message-1", "The answer is Saturday", false, false, false, sender);
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner reactionMessage() {
    return message("reaction-1", "Loved a message", false, false, false, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner blankMessage() {
    return message("blank-1", "   ", false, false, false, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner fromMeMessage() {
    return message("from-me-1", "My own message", true, false, false, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner systemMessage() {
    return message("system-1", "A person joined", false, true, false, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner serviceMessage() {
    return message("service-1", "A person left", false, false, true, "+15555550199");
  }

  private ApiV1ChatChatGuidMessageGet200ResponseDataInner message(
      String guid,
      String text,
      boolean fromMe,
      boolean systemMessage,
      boolean serviceMessage,
      String sender) {
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner().guid("iMessage;+;group");
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle handle =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle().address(sender);
    return new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
        .guid(guid)
        .text(text)
        .isFromMe(fromMe)
        .isSystemMessage(systemMessage)
        .isServiceMessage(serviceMessage)
        .handle(handle)
        .chats(List.of(chat))
        .dateCreated(Instant.parse("2026-08-09T10:00:00Z").toEpochMilli());
  }

  private ResolvedAccount resolved(String accountId, String globalContactName) {
    return new ResolvedAccount(account(accountId, globalContactName), List.of());
  }

  private AgentAccountEntity account(String accountId, String globalContactName) {
    Instant now = Instant.parse("2026-08-09T10:00:00Z");
    AgentAccountEntity account = new AgentAccountEntity(accountId, now, now);
    account.setGlobalContactName(globalContactName);
    return account;
  }
}
