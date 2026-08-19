package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.account.AgentAccountResolver.ResolvedAccount;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantDescriptor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantHint;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesContactIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConversationParticipantResolverTest {
  private static final Instant NOW = Instant.parse("2026-08-10T17:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(30);
  private static final String REQUESTER_ACCOUNT_ID = "account-requester";

  private final AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
  private final BBHttpClientWrapper bb = Mockito.mock(BBHttpClientWrapper.class);
  private ConversationParticipantResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new ConversationParticipantResolver(accountResolver, bb, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void usesConfiguredThenWebsiteThenBlueBubblesNames() {
    AgentAccountEntity configured = account("account-configured", "Configured", "Website");
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved(configured)));

    ParticipantDescriptor configuredResult =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(configuredResult.label()).isEqualTo("Configured");
    verifyNoInteractions(bb);

    Mockito.reset(accountResolver, bb);
    AgentAccountEntity website = account("account-website", null, "Website");
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved(website)));

    ParticipantDescriptor websiteResult =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(websiteResult.label()).isEqualTo("Website");
    verifyNoInteractions(bb);

    Mockito.reset(accountResolver, bb);
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved(account("account-contact", null, null))));
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30)))
        .thenReturn(List.of(contact("Contact", "+15555550199")));

    ParticipantDescriptor contactResult =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(contactResult.label()).isEqualTo("Contact");
    assertThat(contactResult.hint()).isNull();
  }

  @Test
  void unresolvedRawIdentityProducesBoundedHint() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30))).thenReturn(List.of());

    ParticipantDescriptor participant =
        resolver.resolve(
            incoming("tel:+1 (555) 555-0199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(participant.label()).isEqualTo("participant ending 0199");
    assertThat(participant.hint())
        .isEqualTo(new ParticipantHint("participant ending 0199", "+15555550199"));
  }

  @Test
  void loadsBlueBubblesContactsOncePerMappingSession() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30)))
        .thenReturn(List.of(contact("Alice", "+15555550199"), contact("Lee", "+15555550200")));
    ConversationParticipantResolver.Session session =
        new ConversationParticipantResolver.Session(DEADLINE);

    ParticipantDescriptor first =
        resolver.resolve(incoming("+15555550199"), REQUESTER_ACCOUNT_ID, session);
    ParticipantDescriptor second =
        resolver.resolve(incoming("+15555550200"), REQUESTER_ACCOUNT_ID, session);

    assertThat(first.label()).isEqualTo("Alice");
    assertThat(second.label()).isEqualTo("Lee");
    verify(bb, times(1)).getContactIdentitiesForQuestion(Duration.ofSeconds(30));
  }

  @Test
  void requesterIdentityAlwaysUsesYouWithoutContactLookup() {
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved(account(REQUESTER_ACCOUNT_ID, null, null))));

    ParticipantDescriptor participant =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(participant.label()).isEqualTo("you");
    assertThat(participant.hint()).isNull();
    verifyNoInteractions(bb);
  }

  @Test
  void expiredDeadlineSkipsContactLookupAndPreservesMaskedHint() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());

    ParticipantDescriptor participant =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(NOW));

    assertThat(participant.label()).isEqualTo("participant ending 0199");
    assertThat(participant.hint()).isNotNull();
    verifyNoInteractions(bb);
  }

  @Test
  void contactFailureFallsBackAndIsNotRetriedWithinTheSession() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30)))
        .thenThrow(new IllegalStateException("unavailable"));
    ConversationParticipantResolver.Session session =
        new ConversationParticipantResolver.Session(DEADLINE);

    ParticipantDescriptor first =
        resolver.resolve(incoming("+15555550199"), REQUESTER_ACCOUNT_ID, session);
    ParticipantDescriptor second =
        resolver.resolve(incoming("+15555550200"), REQUESTER_ACCOUNT_ID, session);

    assertThat(first.label()).isEqualTo("participant ending 0199");
    assertThat(second.label()).isEqualTo("participant ending 0200");
    verify(bb, times(1)).getContactIdentitiesForQuestion(Duration.ofSeconds(30));
  }

  @Test
  void invalidConfiguredLabelFallsThroughToWebsiteName() {
    AgentAccountEntity account = account("account-2", "x".repeat(161), "Website");
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(resolved(account)));

    ParticipantDescriptor participant =
        resolver.resolve(
            incoming("+15555550199"),
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(participant.label()).isEqualTo("Website");
    verifyNoInteractions(bb);
  }

  @Test
  void journalAccountUsesWebsiteNameAndUnknownJournalAccountHasNoIdentityHint() {
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(resolved(account("account-2", null, "Website"))));
    ConversationParticipantResolver.Session session =
        new ConversationParticipantResolver.Session(DEADLINE);

    ParticipantDescriptor known = resolver.resolve("account-2", REQUESTER_ACCOUNT_ID, session);
    ParticipantDescriptor unknown = resolver.resolve("account-3", REQUESTER_ACCOUNT_ID, session);

    assertThat(known.label()).isEqualTo("Website");
    assertThat(known.hint()).isNull();
    assertThat(unknown.label()).isEqualTo("unknown participant");
    assertThat(unknown.hint()).isNull();
    verifyNoInteractions(bb);
  }

  @Test
  void journalAccountIdentityUsesTheBlueBubblesContactDirectory() {
    AgentAccountEntity account = account("account-2", null, null);
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(resolved(account, identity("account-2", "+15555550199"))));
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30)))
        .thenReturn(List.of(contact("Alice", "+15555550199")));

    ParticipantDescriptor participant =
        resolver.resolve(
            "account-2",
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(participant.label()).isEqualTo("Alice");
    assertThat(participant.hint()).isNull();
  }

  @Test
  void unresolvedJournalAccountIdentityProducesAMaskedHint() {
    AgentAccountEntity account = account("account-2", null, null);
    when(accountResolver.resolveById("account-2"))
        .thenReturn(Optional.of(resolved(account, identity("account-2", "+15555550199"))));
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30))).thenReturn(List.of());

    ParticipantDescriptor participant =
        resolver.resolve(
            "account-2",
            REQUESTER_ACCOUNT_ID,
            new ConversationParticipantResolver.Session(DEADLINE));

    assertThat(participant.label()).isEqualTo("participant ending 0199");
    assertThat(participant.hint())
        .isEqualTo(new ParticipantHint("participant ending 0199", "+15555550199"));
  }

  @Test
  void readOnlyResolutionNeverCreatesOrMergesAccounts() {
    when(accountResolver.resolve(any(IncomingMessage.class))).thenReturn(Optional.empty());
    when(bb.getContactIdentitiesForQuestion(Duration.ofSeconds(30))).thenReturn(List.of());

    resolver.resolve(
        incoming("+15555550199"),
        REQUESTER_ACCOUNT_ID,
        new ConversationParticipantResolver.Session(DEADLINE));

    verify(accountResolver, never()).resolveOrCreate(any(IncomingMessage.class));
  }

  private IncomingMessage incoming(String address) {
    return IncomingMessage.create(
        new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
            .guid("message-1")
            .text("hello")
            .isFromMe(false)
            .isSystemMessage(false)
            .isServiceMessage(false)
            .handle(new ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle().address(address))
            .chats(
                List.of(
                    new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner()
                        .guid("iMessage;+;group")))
            .dateCreated(NOW.toEpochMilli()));
  }

  private AgentAccountEntity account(
      String accountId, String globalContactName, String websiteDisplayName) {
    AgentAccountEntity account = new AgentAccountEntity(accountId, NOW, NOW);
    account.setGlobalContactName(globalContactName);
    account.setWebsiteDisplayName(websiteDisplayName);
    return account;
  }

  private ResolvedAccount resolved(AgentAccountEntity account) {
    return new ResolvedAccount(account, List.of());
  }

  private ResolvedAccount resolved(
      AgentAccountEntity account, AgentAccountIdentityEntity... identities) {
    return new ResolvedAccount(account, List.of(identities));
  }

  private AgentAccountIdentityEntity identity(String accountId, String normalizedIdentifier) {
    return new AgentAccountIdentityEntity(
        "identity-1",
        accountId,
        "imessage_phone",
        normalizedIdentifier,
        normalizedIdentifier,
        NOW,
        NOW);
  }

  private BlueBubblesContactIdentity contact(String name, String address) {
    return new BlueBubblesContactIdentity(name, List.of(address));
  }
}
