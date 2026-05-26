package io.breland.bbagent.server.website;

import io.breland.bbagent.generated.model.WebsiteAccountIdentity;
import io.breland.bbagent.generated.model.WebsiteAccountLink;
import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteAccountRedeemLinkResponse;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.generated.model.WebsiteCalendarAccountSummary;
import io.breland.bbagent.generated.model.WebsiteIntegrationSummary;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.generated.model.WebsiteLinkedIntegrationAccount;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.generated.model.WebsiteModelSelectionResponse;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.agent.tools.gcal.AccountKeyParts;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WebsiteAccountService {
  private static final String DEFAULT_WEBSITE_BASE_URL = "http://localhost:8080";
  private static final int LINK_TOKEN_BYTES = 32;

  private final AgentAccountResolver accountResolver;
  private final WebsiteAccountLinkTokenRepository tokenRepository;
  private final GcalClient gcalClient;
  private final ModelAccessService modelAccessService;
  private final String websiteBaseUrl;
  private final Duration linkTokenTtl;
  private final UmamiAnalyticsService umamiAnalyticsService;
  private final SecureRandom secureRandom = new SecureRandom();

  public WebsiteAccountService(
      AgentAccountResolver accountResolver,
      WebsiteAccountLinkTokenRepository tokenRepository,
      GcalClient gcalClient,
      ModelAccessService modelAccessService,
      @Value("${website.base-url:" + DEFAULT_WEBSITE_BASE_URL + "}") String websiteBaseUrl,
      @Value("${website.account-link-token-ttl-minutes:30}") long linkTokenTtlMinutes,
      @Nullable UmamiAnalyticsService umamiAnalyticsService) {
    this.accountResolver = accountResolver;
    this.tokenRepository = tokenRepository;
    this.gcalClient = gcalClient;
    this.modelAccessService = modelAccessService;
    this.websiteBaseUrl = stripTrailingSlash(websiteBaseUrl);
    this.linkTokenTtl = Duration.ofMinutes(linkTokenTtlMinutes);
    this.umamiAnalyticsService = umamiAnalyticsService;
  }

  @Transactional
  public WebsiteAccountResponse getAccount(Jwt jwt) {
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    return new WebsiteAccountResponse().account(toProfile(account)).links(List.of(toLink(account)));
  }

  @Transactional
  public WebsiteLinkedAccountsResponse listLinkedAccounts(Jwt jwt) {
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    return new WebsiteLinkedAccountsResponse()
        .account(toProfile(account))
        .integrations(List.of(toIntegration(account)));
  }

  @Transactional
  public CreatedLinkToken createLinkToken(IncomingMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("missing message context");
    }
    AgentAccountEntity account =
        accountResolver
            .resolveOrCreate(message)
            .map(AgentAccountResolver.ResolvedAccount::account)
            .orElseThrow(() -> new IllegalArgumentException("missing message identity"));
    String token = newToken();
    String tokenHash = hashToken(token);
    Instant now = Instant.now();
    Instant expiresAt = now.plus(linkTokenTtl);
    tokenRepository.save(
        new WebsiteAccountLinkTokenEntity(
            tokenHash,
            account.getAccountId(),
            message.chatGuid(),
            message.sender(),
            message.service(),
            message.isGroup(),
            message.messageGuid(),
            expiresAt,
            now,
            now));
    trackLinkTokenCreated(account.getAccountId(), message);
    return new CreatedLinkToken(buildLinkUrl(token), expiresAt, account.getAccountId());
  }

  @Transactional(readOnly = true)
  public SenderLinkStatus getLinkStatus(IncomingMessage message) {
    if (message == null) {
      return SenderLinkStatus.empty();
    }
    return accountResolver
        .resolve(message)
        .map(resolved -> toStatus(resolved.account()))
        .orElseGet(SenderLinkStatus::empty);
  }

  @Transactional
  public SenderLinkStatus getLinkStatus(String sender, String chatGuid) {
    return getLinkStatus(IncomingMessage.TRANSPORT_BLUEBUBBLES, sender, chatGuid);
  }

  @Transactional
  public SenderLinkStatus getLinkStatus(String transport, String sender, String chatGuid) {
    String identifier = linkStatusIdentifier(transport, sender, chatGuid);
    if (StringUtils.isBlank(identifier)) {
      return SenderLinkStatus.empty();
    }
    return accountResolver
        .resolveOrCreate(transport, identifier)
        .map(resolved -> toStatus(resolved.account()))
        .orElseGet(SenderLinkStatus::empty);
  }

  @Transactional(readOnly = true)
  public Optional<String> findLinkedAccountEmail(IncomingMessage message) {
    if (message == null) {
      return Optional.empty();
    }
    return accountResolver
        .resolve(message)
        .map(AgentAccountResolver.ResolvedAccount::account)
        .map(AgentAccountEntity::getWebsiteEmail)
        .map(String::trim)
        .filter(StringUtils::isNotBlank);
  }

  @Transactional
  public WebsiteAccountRedeemLinkResponse redeemLink(Jwt jwt, String token) {
    if (StringUtils.isBlank(token)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing link token");
    }
    AgentAccountEntity websiteAccount = accountResolver.upsertWebsiteAccount(jwt);
    WebsiteAccountLinkTokenEntity tokenEntity =
        tokenRepository
            .findById(hashToken(token))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    Instant now = Instant.now();
    validateToken(tokenEntity, websiteAccount, now);
    boolean alreadyLinked = tokenEntity.getRedeemedAt() != null;
    AgentAccountEntity linkedAccount =
        accountResolver.linkWebsiteAccount(tokenEntity.getAccountId(), jwt);
    markTokenRedeemed(tokenEntity, linkedAccount, now);
    trackLinkTokenRedeemed(linkedAccount.getAccountId(), alreadyLinked);

    return new WebsiteAccountRedeemLinkResponse()
        .status(alreadyLinked ? "already_linked" : "linked")
        .link(toLink(linkedAccount));
  }

  @Transactional
  public boolean deleteLinkedAccount(Jwt jwt, String type, String accountKey) {
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    if (StringUtils.isBlank(type) || StringUtils.isBlank(accountKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing linked account");
    }
    boolean deleted =
        switch (type.toLowerCase(Locale.ROOT)) {
          case "gcal" ->
              account.getAccountId().equals(AccountKeyParts.parse(accountKey).accountId())
                  && gcalClient.revokeAccount(accountKey);
          default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type");
        };
    if (!deleted) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Linked integration account not found");
    }
    trackLinkedIntegrationDeleted(account.getAccountId(), type);
    return true;
  }

  @Transactional
  public WebsiteModelSelectionResponse updatePreferredModel(Jwt jwt, String modelKey) {
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    ModelAccessService.ModelSelectionResult result =
        modelAccessService.selectModel(account.getAccountId(), modelKey);
    return new WebsiteModelSelectionResponse()
        .modelAccess(modelAccessService.toWebsiteSummary(result.modelAccess()))
        .message(result.message());
  }

  private void trackLinkTokenCreated(String accountId, IncomingMessage message) {
    if (umamiAnalyticsService == null) {
      return;
    }
    umamiAnalyticsService.track(
        "website_link_token_created",
        "/server/account/link-token",
        accountId,
        Map.of(
            "transport", message.transportOrDefault(),
            "is_group", message.isGroup(),
            "ttl_minutes", linkTokenTtl.toMinutes()));
  }

  private void trackLinkTokenRedeemed(String accountId, boolean alreadyLinked) {
    if (umamiAnalyticsService == null) {
      return;
    }
    umamiAnalyticsService.track(
        "website_account_linked",
        "/server/account/link",
        accountId,
        Map.of("status", alreadyLinked ? "already_linked" : "linked"));
  }

  private void trackLinkedIntegrationDeleted(String accountId, String type) {
    if (umamiAnalyticsService == null) {
      return;
    }
    umamiAnalyticsService.track(
        "website_integration_unlinked",
        "/server/account/integration",
        accountId,
        Map.of("type", StringUtils.lowerCase(StringUtils.trimToEmpty(type))));
  }

  private String linkStatusIdentifier(String transport, String sender, String chatGuid) {
    if (StringUtils.isNotBlank(sender)) {
      return sender;
    }
    if (IncomingMessage.TRANSPORT_LXMF.equalsIgnoreCase(transport)) {
      return IncomingMessage.stripTransportPrefix(chatGuid);
    }
    return sender;
  }

  private SenderLinkStatus toStatus(AgentAccountEntity account) {
    boolean linked = StringUtils.isNotBlank(account.getWebsiteSubject());
    return new SenderLinkStatus(
        account.getAccountId(),
        linked,
        linked,
        1,
        linked ? 1 : 0,
        modelAccessService.toWebsiteSummary(account.getAccountId()),
        linked ? account.getUpdatedAt() : null);
  }

  private WebsiteIntegrationSummary toIntegration(AgentAccountEntity account) {
    List<WebsiteCalendarAccountSummary> gcalAccounts =
        gcalClient.listLinkedAccountsFor(account.getAccountId()).stream()
            .map(
                linked ->
                    new WebsiteCalendarAccountSummary()
                        .accountId(linked.googleAccountId())
                        .accountKey(linked.accountKey())
                        .email(linked.googleAccountId()))
            .toList();
    return new WebsiteIntegrationSummary()
        .link(toLink(account))
        .gcalAccounts(gcalAccounts)
        .linkedAccounts(linkedAccounts(account))
        .modelAccess(modelAccessService.toWebsiteSummary(account.getAccountId()));
  }

  private List<WebsiteLinkedIntegrationAccount> linkedAccounts(AgentAccountEntity account) {
    return gcalClient.listLinkedAccountsFor(account.getAccountId()).stream()
        .map(
            linked ->
                new WebsiteLinkedIntegrationAccount()
                    .type(WebsiteLinkedIntegrationAccount.TypeEnum.GCAL)
                    .accountKey(linked.accountKey())
                    .email(linked.googleAccountId())
                    .label("Google Calendar")
                    .unlinkable(true))
        .toList();
  }

  private WebsiteAccountProfile toProfile(AgentAccountEntity account) {
    return new WebsiteAccountProfile()
        .subject(account.getWebsiteSubject())
        .accountId(account.getAccountId())
        .email(account.getWebsiteEmail())
        .preferredUsername(account.getWebsitePreferredUsername())
        .displayName(account.getWebsiteDisplayName())
        .createdAt(offset(account.getCreatedAt()))
        .updatedAt(offset(account.getUpdatedAt()));
  }

  private WebsiteAccountLink toLink(AgentAccountEntity account) {
    return new WebsiteAccountLink()
        .linkId(account.getAccountId())
        .accountId(account.getAccountId())
        .identities(
            accountResolver.identitiesForAccount(account.getAccountId()).stream()
                .map(this::toIdentity)
                .toList())
        .createdAt(offset(account.getCreatedAt()));
  }

  private WebsiteAccountIdentity toIdentity(AgentAccountIdentityEntity identity) {
    return new WebsiteAccountIdentity()
        .type(WebsiteAccountIdentity.TypeEnum.fromValue(identity.getIdentityType()))
        .identifier(identity.getIdentifier())
        .normalizedIdentifier(identity.getNormalizedIdentifier());
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String newToken() {
    byte[] bytes = new byte[LINK_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    return DigestUtils.sha256Hex(token);
  }

  private String buildLinkUrl(String token) {
    return UriComponentsBuilder.fromUriString(websiteBaseUrl)
        .path("/account/link")
        .queryParam("token", token)
        .build()
        .toUriString();
  }

  private void validateToken(
      WebsiteAccountLinkTokenEntity tokenEntity, AgentAccountEntity account, Instant now) {
    if (tokenEntity.getExpiresAt().isBefore(now)) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link expired");
    }
    if (tokenEntity.getRedeemedAccountId() != null
        && !tokenEntity.getRedeemedAccountId().equals(account.getAccountId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Link already redeemed by another account");
    }
  }

  private void markTokenRedeemed(
      WebsiteAccountLinkTokenEntity tokenEntity, AgentAccountEntity account, Instant now) {
    tokenEntity.setRedeemedAt(
        tokenEntity.getRedeemedAt() == null ? now : tokenEntity.getRedeemedAt());
    tokenEntity.setRedeemedAccountId(account.getAccountId());
    tokenEntity.setUpdatedAt(now);
    tokenRepository.save(tokenEntity);
  }

  private String stripTrailingSlash(String value) {
    String base = StringUtils.defaultIfBlank(value, DEFAULT_WEBSITE_BASE_URL).trim();
    URI.create(base);
    return StringUtils.stripEnd(base, "/");
  }

  public record CreatedLinkToken(String url, Instant expiresAt, String accountId) {}

  public record SenderLinkStatus(
      String accountId,
      boolean linked,
      boolean exactChatLinked,
      int linkCount,
      int exactChatLinkCount,
      WebsiteModelAccessSummary modelAccess,
      Instant linkedAt) {
    public static SenderLinkStatus empty() {
      return new SenderLinkStatus(null, false, false, 0, 0, null, null);
    }
  }
}
