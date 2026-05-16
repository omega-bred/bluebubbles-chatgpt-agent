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
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.gcal.AccountKeyParts;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
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
  private static final String TOKEN_HASH_ALGORITHM = "SHA-256";

  private final AgentAccountResolver accountResolver;
  private final WebsiteAccountLinkTokenRepository tokenRepository;
  private final GcalClient gcalClient;
  private final CoderMcpClient coderMcpClient;
  private final ModelAccessService modelAccessService;
  private final String websiteBaseUrl;
  private final Duration linkTokenTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public WebsiteAccountService(
      AgentAccountResolver accountResolver,
      WebsiteAccountLinkTokenRepository tokenRepository,
      GcalClient gcalClient,
      @Nullable CoderMcpClient coderMcpClient,
      ModelAccessService modelAccessService,
      @Value("${website.base-url:" + DEFAULT_WEBSITE_BASE_URL + "}") String websiteBaseUrl,
      @Value("${website.account-link-token-ttl-minutes:30}") long linkTokenTtlMinutes) {
    this.accountResolver = accountResolver;
    this.tokenRepository = tokenRepository;
    this.gcalClient = gcalClient;
    this.coderMcpClient = coderMcpClient;
    this.modelAccessService = modelAccessService;
    this.websiteBaseUrl = stripTrailingSlash(websiteBaseUrl);
    this.linkTokenTtl = Duration.ofMinutes(linkTokenTtlMinutes);
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
            .orElseThrow(() -> new IllegalArgumentException("missing iMessage identity"));
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
    if (StringUtils.isBlank(sender)) {
      return SenderLinkStatus.empty();
    }
    return accountResolver
        .resolveOrCreate(IncomingMessage.TRANSPORT_BLUEBUBBLES, sender)
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
          case "coder" ->
              account.getAccountId().equals(accountKey)
                  && coderMcpClient != null
                  && coderMcpClient.revoke(account.getAccountId());
          default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type");
        };
    if (!deleted) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Linked integration account not found");
    }
    return true;
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
        .coderLinked(coderMcpClient != null && coderMcpClient.isLinked(account.getAccountId()))
        .gcalAccounts(gcalAccounts)
        .linkedAccounts(linkedAccounts(account))
        .modelAccess(modelAccessService.toWebsiteSummary(account.getAccountId()));
  }

  private List<WebsiteLinkedIntegrationAccount> linkedAccounts(AgentAccountEntity account) {
    Stream<WebsiteLinkedIntegrationAccount> gcalAccounts =
        gcalClient.listLinkedAccountsFor(account.getAccountId()).stream()
            .map(
                linked ->
                    new WebsiteLinkedIntegrationAccount()
                        .type(WebsiteLinkedIntegrationAccount.TypeEnum.GCAL)
                        .accountKey(linked.accountKey())
                        .email(linked.googleAccountId())
                        .label("Google Calendar")
                        .unlinkable(true));
    Stream<WebsiteLinkedIntegrationAccount> coderAccounts =
        coderMcpClient == null
            ? Stream.empty()
            : coderMcpClient
                .findLinkedAccount(account.getAccountId())
                .map(
                    linked ->
                        new WebsiteLinkedIntegrationAccount()
                            .type(WebsiteLinkedIntegrationAccount.TypeEnum.CODER)
                            .accountKey(linked.accountId())
                            .email(linked.email())
                            .label(StringUtils.defaultIfBlank(linked.label(), "Coder"))
                            .unlinkable(true))
                .stream();
    return Stream.concat(gcalAccounts, coderAccounts).toList();
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
    try {
      MessageDigest digest = MessageDigest.getInstance(TOKEN_HASH_ALGORITHM);
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(TOKEN_HASH_ALGORITHM + " not available", e);
    }
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
    String base = value == null || value.isBlank() ? DEFAULT_WEBSITE_BASE_URL : value.trim();
    URI.create(base);
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
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
