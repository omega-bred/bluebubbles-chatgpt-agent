package io.breland.bbagent.server.appclip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.ConversationSummary;
import io.breland.bbagent.generated.model.SubscriptionSummaryResponse;
import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionEntity;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.conversation.ConversationSettingsService;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppClipSessionServiceTest {
  @Test
  void createSessionExchangesLinkTokenForScopedAppClipToken() {
    AppClipSessionRepository sessionRepository = mock(AppClipSessionRepository.class);
    WebsiteAccountLinkTokenRepository linkTokenRepository =
        mock(WebsiteAccountLinkTokenRepository.class);
    AgentAccountRepository accountRepository = mock(AgentAccountRepository.class);
    WebsiteAccountService websiteAccountService = mock(WebsiteAccountService.class);
    ConversationSettingsService conversationSettingsService =
        mock(ConversationSettingsService.class);
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    Instant now = Instant.now();
    String linkToken = "link-token";
    String accountId = "account-1";
    WebsiteAccountLinkTokenEntity linkTokenEntity =
        new WebsiteAccountLinkTokenEntity(
            DigestUtils.sha256Hex(linkToken),
            accountId,
            WebsiteAccountService.LINK_PURPOSE_ACCOUNT_LINK,
            "chat-guid",
            "+15555550123",
            "iMessage",
            false,
            "message-guid",
            now.plusSeconds(300),
            now,
            now);
    when(linkTokenRepository.findById(DigestUtils.sha256Hex(linkToken)))
        .thenReturn(Optional.of(linkTokenEntity));
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(new AgentAccountEntity(accountId, now, now)));
    when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(websiteAccountService.listLinkedAccounts(accountId))
        .thenReturn(
            new WebsiteLinkedAccountsResponse()
                .account(new WebsiteAccountProfile().accountId(accountId))
                .integrations(List.of()));
    when(subscriptionService.getAccountSubscription(accountId))
        .thenReturn(
            new SubscriptionSummaryResponse()
                .accountId(accountId)
                .isPremium(false)
                .entitlementSource("none")
                .plans(List.of())
                .subscriptions(List.of()));
    when(subscriptionService.storeKitProductIds())
        .thenReturn(List.of("land.bre.bluechat.premium.monthly"));

    AppClipSessionService service =
        new AppClipSessionService(
            sessionRepository,
            linkTokenRepository,
            accountRepository,
            websiteAccountService,
            conversationSettingsService,
            subscriptionService,
            30);

    var response = service.createSession(linkToken);

    ArgumentCaptor<AppClipSessionEntity> savedSession =
        ArgumentCaptor.forClass(AppClipSessionEntity.class);
    verify(sessionRepository).save(savedSession.capture());
    assertThat(response.getSessionToken()).isNotBlank();
    assertThat(response.getPurpose()).isEqualTo(AppClipSessionResponse.PurposeEnum.ACCOUNT_LINK);
    assertThat(response.getAccount().getAccountId()).isEqualTo(accountId);
    assertThat(savedSession.getValue().getAccountId()).isEqualTo(accountId);
    assertThat(savedSession.getValue().getPurpose())
        .isEqualTo(WebsiteAccountService.LINK_PURPOSE_ACCOUNT_LINK);
    assertThat(savedSession.getValue().getSourceLinkTokenHash())
        .isEqualTo(DigestUtils.sha256Hex(linkToken));
    assertThat(linkTokenEntity.getRedeemedAccountId()).isEqualTo(accountId);
    assertThat(linkTokenEntity.getRedeemedAt()).isNotNull();
  }

  @Test
  void createSessionIncludesConversationSettingsForConversationPurposeToken() {
    AppClipSessionRepository sessionRepository = mock(AppClipSessionRepository.class);
    WebsiteAccountLinkTokenRepository linkTokenRepository =
        mock(WebsiteAccountLinkTokenRepository.class);
    AgentAccountRepository accountRepository = mock(AgentAccountRepository.class);
    WebsiteAccountService websiteAccountService = mock(WebsiteAccountService.class);
    ConversationSettingsService conversationSettingsService =
        mock(ConversationSettingsService.class);
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    Instant now = Instant.now();
    String linkToken = "conversation-link-token";
    String accountId = "account-1";
    WebsiteAccountLinkTokenEntity linkTokenEntity =
        new WebsiteAccountLinkTokenEntity(
            DigestUtils.sha256Hex(linkToken),
            accountId,
            WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS,
            "chat-guid",
            "+15555550123",
            "iMessage",
            true,
            "message-guid",
            now.plusSeconds(300),
            now,
            now);
    when(linkTokenRepository.findById(DigestUtils.sha256Hex(linkToken)))
        .thenReturn(Optional.of(linkTokenEntity));
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(new AgentAccountEntity(accountId, now, now)));
    when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(websiteAccountService.listLinkedAccounts(accountId))
        .thenReturn(
            new WebsiteLinkedAccountsResponse()
                .account(new WebsiteAccountProfile().accountId(accountId))
                .integrations(List.of()));
    when(subscriptionService.getAccountSubscription(accountId))
        .thenReturn(
            new SubscriptionSummaryResponse()
                .accountId(accountId)
                .isPremium(false)
                .entitlementSource("none")
                .plans(List.of())
                .subscriptions(List.of()));
    when(subscriptionService.storeKitProductIds()).thenReturn(List.of());
    when(conversationSettingsService.getSettings(accountId, "chat-guid"))
        .thenReturn(
            new ConversationSettingsResponse()
                .conversation(
                    new ConversationSummary()
                        .chatGuid("chat-guid")
                        .displayName("Project Chat")
                        .participants(List.of()))
                .currentResponsiveness(
                    ConversationSettingsResponse.CurrentResponsivenessEnum.DEFAULT)
                .currentResponsivenessLabel("Balanced")
                .options(List.of()));

    AppClipSessionService service =
        new AppClipSessionService(
            sessionRepository,
            linkTokenRepository,
            accountRepository,
            websiteAccountService,
            conversationSettingsService,
            subscriptionService,
            30);

    var response = service.createSession(linkToken);

    ArgumentCaptor<AppClipSessionEntity> savedSession =
        ArgumentCaptor.forClass(AppClipSessionEntity.class);
    verify(sessionRepository).save(savedSession.capture());
    assertThat(response.getPurpose())
        .isEqualTo(AppClipSessionResponse.PurposeEnum.CONVERSATION_SETTINGS);
    assertThat(response.getConversationSettings().getConversation().getDisplayName())
        .isEqualTo("Project Chat");
    assertThat(savedSession.getValue().getPurpose())
        .isEqualTo(WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS);
    assertThat(savedSession.getValue().getChatGuid()).isEqualTo("chat-guid");
  }
}
