package io.breland.bbagent.server.appclip;

import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.generated.model.SubscriptionSummaryResponse;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionEntity;
import io.breland.bbagent.server.agent.persistence.appclip.AppClipSessionRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.subscriptions.SubscriptionService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppClipSessionService {
  public static final String APP_CLIP_ACCOUNT_ID_CLAIM = "bbagent_account_id";
  private static final int SESSION_TOKEN_BYTES = 32;

  private final AppClipSessionRepository sessionRepository;
  private final WebsiteAccountLinkTokenRepository linkTokenRepository;
  private final AgentAccountRepository accountRepository;
  private final WebsiteAccountService websiteAccountService;
  private final SubscriptionService subscriptionService;
  private final Duration sessionTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public AppClipSessionService(
      AppClipSessionRepository sessionRepository,
      WebsiteAccountLinkTokenRepository linkTokenRepository,
      AgentAccountRepository accountRepository,
      WebsiteAccountService websiteAccountService,
      SubscriptionService subscriptionService,
      @Value("${appclip.session-token-ttl-days:30}") long sessionTokenTtlDays) {
    this.sessionRepository = sessionRepository;
    this.linkTokenRepository = linkTokenRepository;
    this.accountRepository = accountRepository;
    this.websiteAccountService = websiteAccountService;
    this.subscriptionService = subscriptionService;
    this.sessionTtl = Duration.ofDays(Math.max(1, sessionTokenTtlDays));
  }

  @Transactional
  public AppClipSessionResponse createSession(String linkToken) {
    if (StringUtils.isBlank(linkToken)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing link token");
    }
    String linkTokenHash = hash(linkToken);
    WebsiteAccountLinkTokenEntity linkTokenEntity =
        linkTokenRepository
            .findById(linkTokenHash)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    Instant now = Instant.now();
    if (linkTokenEntity.getExpiresAt().isBefore(now)) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link expired");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(linkTokenEntity.getAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    String sessionToken = newToken();
    Instant expiresAt = now.plus(sessionTtl);
    sessionRepository.save(
        new AppClipSessionEntity(
            hash(sessionToken), account.getAccountId(), linkTokenHash, expiresAt, now));
    markLinkTokenClaimed(linkTokenEntity, account, now);
    return response(sessionToken, account.getAccountId(), expiresAt);
  }

  @Transactional
  public Optional<AuthenticatedAppClipSession> authenticate(String sessionToken) {
    if (StringUtils.isBlank(sessionToken)) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    return sessionRepository
        .findById(hash(sessionToken))
        .filter(session -> session.getRevokedAt() == null)
        .filter(session -> session.getExpiresAt().isAfter(now))
        .flatMap(
            session ->
                accountRepository
                    .findById(session.getAccountId())
                    .map(
                        account -> {
                          session.setLastUsedAt(now);
                          sessionRepository.save(session);
                          return new AuthenticatedAppClipSession(
                              account.getAccountId(), session.getExpiresAt());
                        }));
  }

  @Transactional(readOnly = true)
  public AppClipSessionResponse getSession(String sessionToken, String accountId) {
    AppClipSessionEntity session =
        sessionRepository
            .findById(hash(sessionToken))
            .filter(candidate -> candidate.getAccountId().equals(accountId))
            .filter(candidate -> candidate.getRevokedAt() == null)
            .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid App Clip session"));
    return response(sessionToken, accountId, session.getExpiresAt());
  }

  public List<String> storeKitProductIds() {
    return subscriptionService.storeKitProductIds();
  }

  private AppClipSessionResponse response(
      String sessionToken, String accountId, Instant expiresAt) {
    WebsiteLinkedAccountsResponse linkedAccounts =
        websiteAccountService.listLinkedAccounts(accountId);
    SubscriptionSummaryResponse subscription =
        subscriptionService.getAccountSubscription(accountId);
    return new AppClipSessionResponse()
        .sessionToken(sessionToken)
        .expiresAt(offset(expiresAt))
        .account(linkedAccounts.getAccount())
        .linkedAccounts(linkedAccounts)
        .subscription(subscription)
        .appAccountToken(AppAccountTokens.forAccountId(accountId))
        .storekitProductIds(storeKitProductIds());
  }

  private void markLinkTokenClaimed(
      WebsiteAccountLinkTokenEntity linkTokenEntity, AgentAccountEntity account, Instant now) {
    if (linkTokenEntity.getRedeemedAt() == null) {
      linkTokenEntity.setRedeemedAt(now);
    }
    if (StringUtils.isBlank(linkTokenEntity.getRedeemedAccountId())) {
      linkTokenEntity.setRedeemedAccountId(account.getAccountId());
    }
    linkTokenEntity.setUpdatedAt(now);
    linkTokenRepository.save(linkTokenEntity);
  }

  private String newToken() {
    byte[] bytes = new byte[SESSION_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hash(String token) {
    return DigestUtils.sha256Hex(token);
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public record AuthenticatedAppClipSession(String accountId, Instant expiresAt) {}
}
