package io.breland.bbagent.server.appclip;

import static io.breland.bbagent.server.TimeSupport.offset;

import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.SubscriptionSummaryResponse;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionEntity;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.conversation.ConversationSettingsService;
import io.breland.bbagent.server.sessions.SessionTokens;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppClipSessionService {
  public static final String APP_CLIP_ACCOUNT_ID_CLAIM = "bbagent_account_id";
  public static final String APP_CLIP_PURPOSE_CLAIM = "bbagent_purpose";
  public static final String APP_CLIP_CHAT_GUID_CLAIM = "bbagent_chat_guid";
  private static final int SESSION_TOKEN_BYTES = 32;

  private final AppClipSessionRepository sessionRepository;
  private final WebsiteAccountLinkTokenRepository linkTokenRepository;
  private final AgentAccountRepository accountRepository;
  private final WebsiteAccountService websiteAccountService;
  private final ConversationSettingsService conversationSettingsService;
  private final SubscriptionService subscriptionService;
  private final Duration sessionTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public AppClipSessionService(
      AppClipSessionRepository sessionRepository,
      WebsiteAccountLinkTokenRepository linkTokenRepository,
      AgentAccountRepository accountRepository,
      WebsiteAccountService websiteAccountService,
      ConversationSettingsService conversationSettingsService,
      SubscriptionService subscriptionService,
      @Value("${appclip.session-token-ttl-days:30}") long sessionTokenTtlDays) {
    this.sessionRepository = sessionRepository;
    this.linkTokenRepository = linkTokenRepository;
    this.accountRepository = accountRepository;
    this.websiteAccountService = websiteAccountService;
    this.conversationSettingsService = conversationSettingsService;
    this.subscriptionService = subscriptionService;
    this.sessionTtl = Duration.ofDays(Math.max(1, sessionTokenTtlDays));
  }

  @Transactional
  public AppClipSessionResponse createSession(String linkToken) {
    if (StringUtils.isBlank(linkToken)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing link token");
    }
    String linkTokenHash = SessionTokens.sha256Hash(linkToken);
    WebsiteAccountLinkTokenEntity linkTokenEntity =
        linkTokenRepository
            .findForRedemption(linkTokenHash)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    Instant now = Instant.now();
    if (!linkTokenEntity.getExpiresAt().isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link expired");
    }
    if (linkTokenEntity.getRedeemedAt() != null || linkTokenEntity.getRedeemedAccountId() != null) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link already used");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(linkTokenEntity.getAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    String sessionToken = SessionTokens.randomUrlToken(secureRandom, SESSION_TOKEN_BYTES);
    Instant expiresAt = now.plus(sessionTtl);
    String purpose = normalizedPurpose(linkTokenEntity.getPurpose());
    String chatGuid = linkTokenEntity.getChatGuid();
    sessionRepository.save(
        new AppClipSessionEntity(
            SessionTokens.sha256Hash(sessionToken),
            account.getAccountId(),
            purpose,
            chatGuid,
            linkTokenHash,
            expiresAt,
            now));
    markLinkTokenClaimed(linkTokenEntity, account, now);
    return response(sessionToken, account.getAccountId(), purpose, chatGuid, expiresAt);
  }

  @Transactional
  public Optional<AuthenticatedAppClipSession> authenticate(String sessionToken) {
    if (StringUtils.isBlank(sessionToken)) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    String tokenHash = SessionTokens.sha256Hash(sessionToken);
    AppClipSessionEntity session =
        sessionRepository
            .findById(tokenHash)
            .filter(candidate -> candidate.getRevokedAt() == null)
            .filter(candidate -> candidate.getExpiresAt().isAfter(now))
            .orElse(null);
    if (session == null) {
      return Optional.empty();
    }
    String nextToken = SessionTokens.randomUrlToken(secureRandom, SESSION_TOKEN_BYTES);
    if (sessionRepository.rotateToken(
            tokenHash, SessionTokens.sha256Hash(nextToken), session.getAccountId(), now)
        != 1) {
      return Optional.empty();
    }
    return Optional.of(
        new AuthenticatedAppClipSession(
            session.getAccountId(),
            normalizedPurpose(session.getPurpose()),
            session.getChatGuid(),
            session.getExpiresAt(),
            nextToken));
  }

  @Transactional(readOnly = true)
  public AppClipSessionResponse getSession(AuthenticatedAppClipSession session) {
    return response(
        session.nextToken(),
        session.accountId(),
        session.purpose(),
        session.chatGuid(),
        session.expiresAt());
  }

  private AppClipSessionResponse response(
      String sessionToken, String accountId, String purpose, String chatGuid, Instant expiresAt) {
    WebsiteLinkedAccountsResponse linkedAccounts =
        websiteAccountService.listLinkedAccounts(accountId);
    SubscriptionSummaryResponse subscription =
        subscriptionService.getAccountSubscription(accountId);
    ConversationSettingsResponse conversationSettings =
        WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS.equals(purpose)
                && StringUtils.isNotBlank(chatGuid)
            ? conversationSettingsService.getSettings(accountId, chatGuid)
            : null;
    return new AppClipSessionResponse()
        .sessionToken(sessionToken)
        .purpose(AppClipSessionResponse.PurposeEnum.fromValue(purpose))
        .expiresAt(offset(expiresAt))
        .account(linkedAccounts.getAccount())
        .linkedAccounts(linkedAccounts)
        .subscription(subscription)
        .conversationSettings(conversationSettings);
  }

  private void markLinkTokenClaimed(
      WebsiteAccountLinkTokenEntity linkTokenEntity, AgentAccountEntity account, Instant now) {
    linkTokenEntity.setRedeemedAt(now);
    linkTokenEntity.setRedeemedAccountId(account.getAccountId());
    linkTokenEntity.setUpdatedAt(now);
    linkTokenRepository.save(linkTokenEntity);
  }

  private String normalizedPurpose(String purpose) {
    if (WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS.equals(purpose)) {
      return WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS;
    }
    return WebsiteAccountService.LINK_PURPOSE_ACCOUNT_LINK;
  }

  public record AuthenticatedAppClipSession(
      String accountId, String purpose, String chatGuid, Instant expiresAt, String nextToken) {}
}
