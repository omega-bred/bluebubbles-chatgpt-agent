package io.breland.bbagent.server.website;

import io.breland.bbagent.generated.model.WebsiteAccountLink;
import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteAccountRedeemLinkResponse;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.generated.model.WebsiteCalendarAccountSummary;
import io.breland.bbagent.generated.model.WebsiteIntegrationSummary;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.generated.model.WebsiteLinkedIntegrationAccount;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.AccountIdentityAliasService;
import io.breland.bbagent.server.agent.AgentAccountIdentity;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountSenderLinkEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountSenderLinkRepository;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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

  private final WebsiteAccountRepository accountRepository;
  private final WebsiteAccountLinkTokenRepository tokenRepository;
  private final WebsiteAccountSenderLinkRepository linkRepository;
  private final GcalClient gcalClient;
  private final CoderMcpClient coderMcpClient;
  private final ModelAccessService modelAccessService;
  private final AccountIdentityAliasService accountIdentityAliasService;
  private final String websiteBaseUrl;
  private final Duration linkTokenTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public WebsiteAccountService(
      WebsiteAccountRepository accountRepository,
      WebsiteAccountLinkTokenRepository tokenRepository,
      WebsiteAccountSenderLinkRepository linkRepository,
      GcalClient gcalClient,
      CoderMcpClient coderMcpClient,
      ModelAccessService modelAccessService,
      @Nullable AccountIdentityAliasService accountIdentityAliasService,
      @Value("${website.base-url:" + DEFAULT_WEBSITE_BASE_URL + "}") String websiteBaseUrl,
      @Value("${website.account-link-token-ttl-minutes:30}") long linkTokenTtlMinutes) {
    this.accountRepository = accountRepository;
    this.tokenRepository = tokenRepository;
    this.linkRepository = linkRepository;
    this.gcalClient = gcalClient;
    this.coderMcpClient = coderMcpClient;
    this.modelAccessService = modelAccessService;
    this.accountIdentityAliasService = accountIdentityAliasService;
    this.websiteBaseUrl = stripTrailingSlash(websiteBaseUrl);
    this.linkTokenTtl = Duration.ofMinutes(linkTokenTtlMinutes);
  }

  @Transactional
  public WebsiteAccountResponse getAccount(Jwt jwt) {
    WebsiteAccountEntity account = upsertAccount(jwt);
    return new WebsiteAccountResponse()
        .account(toProfile(account))
        .links(
            linkRepository
                .findAllByAccountSubjectOrderByCreatedAtDesc(account.getKeycloakSubject())
                .stream()
                .map(this::toLink)
                .toList());
  }

  @Transactional
  public WebsiteLinkedAccountsResponse listLinkedAccounts(Jwt jwt) {
    WebsiteAccountEntity account = upsertAccount(jwt);
    List<WebsiteIntegrationSummary> integrations =
        linkRepository
            .findAllByAccountSubjectOrderByCreatedAtDesc(account.getKeycloakSubject())
            .stream()
            .peek(link -> recordAliases(account, link))
            .map(this::toIntegration)
            .toList();
    return new WebsiteLinkedAccountsResponse()
        .account(toProfile(account))
        .integrations(integrations);
  }

  @Transactional
  public CreatedLinkToken createLinkToken(IncomingMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("missing message context");
    }
    AgentAccountIdentity identity = AgentAccountIdentity.from(message);
    if (identity.accountBase().isBlank()) {
      throw new IllegalArgumentException("missing iMessage identity");
    }
    recordAliases(message);
    String accountBase = preferredAccountBaseForWrite(identity.accountBase());
    String coderAccountBase = preferredAccountBaseForWrite(identity.coderAccountBase());
    String gcalAccountBase = preferredAccountBaseForWrite(identity.gcalAccountBase());

    String token = newToken();
    String tokenHash = hashToken(token);
    Instant now = Instant.now();
    Instant expiresAt = now.plus(linkTokenTtl);
    WebsiteAccountLinkTokenEntity entity =
        new WebsiteAccountLinkTokenEntity(
            tokenHash,
            accountBase,
            coderAccountBase,
            gcalAccountBase,
            message.chatGuid(),
            message.sender(),
            message.service(),
            message.isGroup(),
            message.messageGuid(),
            expiresAt,
            now,
            now);
    tokenRepository.save(entity);
    String url = buildLinkUrl(token);
    return new CreatedLinkToken(url, expiresAt, accountBase);
  }

  @Transactional(readOnly = true)
  public SenderLinkStatus getLinkStatus(IncomingMessage message) {
    if (message == null) {
      return SenderLinkStatus.empty();
    }
    return getLinkStatus(message.sender(), message.chatGuid());
  }

  @Transactional(readOnly = true)
  public Optional<String> findLinkedAccountEmail(IncomingMessage message) {
    if (message == null) {
      return Optional.empty();
    }
    AgentAccountIdentity identity = AgentAccountIdentity.from(message);
    if (identity.accountBase().isBlank()) {
      return Optional.empty();
    }
    List<String> accountBases = accountBaseCandidates(identity.accountBase());
    List<String> coderAccountBases = accountBaseCandidates(identity.coderAccountBase());
    List<String> gcalAccountBases = accountBaseCandidates(identity.gcalAccountBase());
    Optional<WebsiteAccountSenderLinkEntity> exactLink =
        linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                accountBases, coderAccountBases, gcalAccountBases)
            .stream()
            .findFirst();
    Optional<WebsiteAccountSenderLinkEntity> link =
        exactLink.or(
            () ->
                linkRepository.findAllByAccountBaseInOrderByCreatedAtDesc(accountBases).stream()
                    .findFirst());
    return link.flatMap(linkEntity -> accountRepository.findById(linkEntity.getAccountSubject()))
        .map(WebsiteAccountEntity::getEmail)
        .map(String::trim)
        .filter(email -> !email.isBlank());
  }

  @Transactional(readOnly = true)
  public SenderLinkStatus getLinkStatus(String sender, String chatGuid) {
    AgentAccountIdentity identity = AgentAccountIdentity.from(sender, chatGuid);
    if (identity.accountBase().isBlank()) {
      return SenderLinkStatus.empty();
    }
    List<String> accountBases = accountBaseCandidates(identity.accountBase());
    List<String> coderAccountBases = accountBaseCandidates(identity.coderAccountBase());
    List<String> gcalAccountBases = accountBaseCandidates(identity.gcalAccountBase());
    List<WebsiteAccountSenderLinkEntity> accountLinks =
        linkRepository.findAllByAccountBaseInOrderByCreatedAtDesc(accountBases);
    List<WebsiteAccountSenderLinkEntity> exactLinks =
        linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                accountBases, coderAccountBases, gcalAccountBases);
    Instant latestLinkedAt =
        exactLinks.stream()
            .findFirst()
            .or(() -> accountLinks.stream().findFirst())
            .map(WebsiteAccountSenderLinkEntity::getCreatedAt)
            .orElse(null);
    return new SenderLinkStatus(
        accountBases.getFirst(),
        coderAccountBases.getFirst(),
        gcalAccountBases.getFirst(),
        !accountLinks.isEmpty(),
        !exactLinks.isEmpty(),
        accountLinks.size(),
        exactLinks.size(),
        modelAccessService.toWebsiteSummary(accountBases.getFirst()),
        latestLinkedAt);
  }

  @Transactional
  public WebsiteAccountRedeemLinkResponse redeemLink(Jwt jwt, String token) {
    if (token == null || token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing link token");
    }
    WebsiteAccountEntity account = upsertAccount(jwt);
    String tokenHash = hashToken(token);
    WebsiteAccountLinkTokenEntity tokenEntity =
        tokenRepository
            .findById(tokenHash)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    Instant now = Instant.now();
    validateToken(tokenEntity, account, now);

    boolean tokenAlreadyRedeemed = tokenEntity.getRedeemedAccountSubject() != null;
    Optional<WebsiteAccountSenderLinkEntity> existingLink =
        linkRepository
            .findAllByAccountSubjectAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                account.getKeycloakSubject(),
                accountBaseCandidates(tokenEntity.getCoderAccountBase()),
                accountBaseCandidates(tokenEntity.getGcalAccountBase()))
            .stream()
            .findFirst();
    if (tokenAlreadyRedeemed && existingLink.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Link token already consumed");
    }
    boolean alreadyLinked = existingLink.isPresent();
    WebsiteAccountSenderLinkEntity link =
        existingLink.orElseGet(() -> newSenderLink(account, tokenEntity, now));
    link.setUpdatedAt(now);
    link = linkRepository.save(link);
    recordAliases(account, tokenEntity);

    markTokenRedeemed(tokenEntity, account, now);

    WebsiteAccountRedeemLinkResponse response = new WebsiteAccountRedeemLinkResponse();
    response.setStatus(alreadyLinked ? "already_linked" : "linked");
    response.setLink(toLink(link));
    return response;
  }

  @Transactional
  public boolean deleteLink(Jwt jwt, String linkId) {
    WebsiteAccountEntity account = upsertAccount(jwt);
    WebsiteAccountSenderLinkEntity link =
        linkRepository
            .findByLinkIdAndAccountSubject(linkId, account.getKeycloakSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    linkRepository.delete(link);
    return true;
  }

  @Transactional
  public boolean deleteLinkedAccount(Jwt jwt, String type, String accountKey) {
    WebsiteAccountEntity account = upsertAccount(jwt);
    if (StringUtils.isBlank(type) || StringUtils.isBlank(accountKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing linked account");
    }
    WebsiteLinkedIntegrationAccount linkedAccount =
        linkRepository
            .findAllByAccountSubjectOrderByCreatedAtDesc(account.getKeycloakSubject())
            .stream()
            .flatMap(link -> linkedAccounts(link).stream())
            .filter(linked -> accountKey.equals(linked.getAccountKey()))
            .filter(linked -> type.equalsIgnoreCase(linked.getType().getValue()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Linked integration account not found"));
    boolean deleted =
        switch (linkedAccount.getType()) {
          case GCAL -> gcalClient.revokeAccount(accountKey);
          case CODER -> coderMcpClient != null && coderMcpClient.revoke(accountKey);
        };
    if (!deleted) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Linked integration account not found");
    }
    return true;
  }

  private WebsiteAccountEntity upsertAccount(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing account subject");
    }
    Instant now = Instant.now();
    WebsiteAccountEntity account =
        accountRepository
            .findById(jwt.getSubject())
            .orElseGet(
                () -> new WebsiteAccountEntity(jwt.getSubject(), null, null, null, now, now));
    account.setEmail(jwt.getClaimAsString("email"));
    account.setPreferredUsername(jwt.getClaimAsString("preferred_username"));
    account.setDisplayName(resolveDisplayName(jwt));
    account.setUpdatedAt(now);
    return accountRepository.save(account);
  }

  private WebsiteAccountSenderLinkEntity newSenderLink(
      WebsiteAccountEntity account, WebsiteAccountLinkTokenEntity token, Instant now) {
    return new WebsiteAccountSenderLinkEntity(
        UUID.randomUUID().toString(),
        account.getKeycloakSubject(),
        token.getAccountBase(),
        token.getCoderAccountBase(),
        token.getGcalAccountBase(),
        token.getChatGuid(),
        token.getSender(),
        token.getService(),
        token.isGroup(),
        token.getSourceMessageGuid(),
        token.getTokenHash(),
        now,
        now);
  }

  private WebsiteIntegrationSummary toIntegration(WebsiteAccountSenderLinkEntity link) {
    List<WebsiteCalendarAccountSummary> gcalAccounts =
        gcalClient.listLinkedAccountsFor(link.getGcalAccountBase()).stream()
            .map(
                account ->
                    new WebsiteCalendarAccountSummary()
                        .accountId(account.accountId())
                        .accountKey(account.accountKey())
                        .email(account.accountId()))
            .toList();
    return new WebsiteIntegrationSummary()
        .link(toLink(link))
        .coderLinked(coderMcpClient != null && coderMcpClient.isLinked(link.getCoderAccountBase()))
        .gcalAccounts(gcalAccounts)
        .linkedAccounts(linkedAccounts(link))
        .modelAccess(modelAccessService.toWebsiteSummary(link.getAccountBase()));
  }

  private List<WebsiteLinkedIntegrationAccount> linkedAccounts(
      WebsiteAccountSenderLinkEntity link) {
    Stream<WebsiteLinkedIntegrationAccount> gcalAccounts =
        gcalClient.listLinkedAccountsFor(link.getGcalAccountBase()).stream()
            .map(
                account ->
                    new WebsiteLinkedIntegrationAccount()
                        .type(WebsiteLinkedIntegrationAccount.TypeEnum.GCAL)
                        .accountKey(account.accountKey())
                        .email(account.accountId())
                        .label("Google Calendar")
                        .unlinkable(true));
    Stream<WebsiteLinkedIntegrationAccount> coderAccounts =
        coderMcpClient == null
            ? Stream.empty()
            : coderMcpClient
                .findLinkedAccount(link.getCoderAccountBase())
                .map(
                    account ->
                        new WebsiteLinkedIntegrationAccount()
                            .type(WebsiteLinkedIntegrationAccount.TypeEnum.CODER)
                            .accountKey(account.accountBase())
                            .email(account.email())
                            .label(
                                account.label() == null || account.label().isBlank()
                                    ? "Coder"
                                    : account.label())
                            .unlinkable(true))
                .stream();
    return Stream.concat(gcalAccounts, coderAccounts).toList();
  }

  private WebsiteAccountProfile toProfile(WebsiteAccountEntity account) {
    return new WebsiteAccountProfile()
        .subject(account.getKeycloakSubject())
        .email(account.getEmail())
        .preferredUsername(account.getPreferredUsername())
        .displayName(account.getDisplayName())
        .createdAt(offset(account.getCreatedAt()))
        .updatedAt(offset(account.getUpdatedAt()));
  }

  private WebsiteAccountLink toLink(WebsiteAccountSenderLinkEntity link) {
    return new WebsiteAccountLink()
        .linkId(link.getLinkId())
        .accountBase(link.getAccountBase())
        .coderAccountBase(link.getCoderAccountBase())
        .gcalAccountBase(link.getGcalAccountBase())
        .chatGuid(link.getChatGuid())
        .sender(link.getSender())
        .service(link.getService())
        .isGroup(link.isGroup())
        .createdAt(offset(link.getCreatedAt()));
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String resolveDisplayName(Jwt jwt) {
    String name = jwt.getClaimAsString("name");
    if (name != null && !name.isBlank()) {
      return name;
    }
    String given = jwt.getClaimAsString("given_name");
    String family = jwt.getClaimAsString("family_name");
    String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
    if (!joined.isBlank()) {
      return joined;
    }
    return jwt.getClaimAsString("preferred_username");
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
      WebsiteAccountLinkTokenEntity tokenEntity, WebsiteAccountEntity account, Instant now) {
    if (tokenEntity.getExpiresAt().isBefore(now)) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link expired");
    }
    if (tokenEntity.getRedeemedAccountSubject() != null
        && !tokenEntity.getRedeemedAccountSubject().equals(account.getKeycloakSubject())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Link already redeemed by another account");
    }
  }

  private void markTokenRedeemed(
      WebsiteAccountLinkTokenEntity tokenEntity, WebsiteAccountEntity account, Instant now) {
    tokenEntity.setRedeemedAt(
        tokenEntity.getRedeemedAt() == null ? now : tokenEntity.getRedeemedAt());
    tokenEntity.setRedeemedAccountSubject(account.getKeycloakSubject());
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

  private void recordAliases(IncomingMessage message) {
    if (accountIdentityAliasService != null) {
      accountIdentityAliasService.recordMessageAliases(message);
    }
  }

  private void recordAliases(WebsiteAccountEntity account, WebsiteAccountLinkTokenEntity token) {
    if (account == null || token == null || token.isGroup()) {
      return;
    }
    recordLinkedAccountAliases(account, token.getAccountBase(), token.getSender());
  }

  private void recordAliases(WebsiteAccountEntity account, WebsiteAccountSenderLinkEntity link) {
    if (account == null || link == null || link.isGroup()) {
      return;
    }
    recordLinkedAccountAliases(account, link.getAccountBase(), link.getSender());
  }

  private void recordLinkedAccountAliases(
      WebsiteAccountEntity account, String accountBase, String sender) {
    if (accountIdentityAliasService == null || StringUtils.isBlank(accountBase)) {
      return;
    }
    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    aliases.add(accountBase);
    if (StringUtils.isNotBlank(sender)) {
      aliases.add(sender);
    }
    if (StringUtils.isNotBlank(account.getEmail())) {
      aliases.add(account.getEmail());
    }
    accountIdentityAliasService.recordAliases(
        IncomingMessage.TRANSPORT_BLUEBUBBLES, aliases, accountBase);
  }

  private List<String> accountBaseCandidates(String accountBase) {
    if (accountIdentityAliasService == null) {
      return accountBase == null || accountBase.isBlank() ? List.of() : List.of(accountBase);
    }
    List<String> candidates = accountIdentityAliasService.accountBaseCandidates(accountBase);
    return candidates.isEmpty() && accountBase != null && !accountBase.isBlank()
        ? List.of(accountBase)
        : candidates;
  }

  private String preferredAccountBaseForWrite(String accountBase) {
    if (accountIdentityAliasService == null) {
      return accountBase;
    }
    return accountIdentityAliasService.preferredAccountBaseForWrite(accountBase);
  }

  public record CreatedLinkToken(String url, Instant expiresAt, String accountBase) {}

  public record SenderLinkStatus(
      String accountBase,
      String coderAccountBase,
      String gcalAccountBase,
      boolean linked,
      boolean exactChatLinked,
      int linkCount,
      int exactChatLinkCount,
      WebsiteModelAccessSummary modelAccess,
      Instant linkedAt) {
    public static SenderLinkStatus empty() {
      return new SenderLinkStatus(null, null, null, false, false, 0, 0, null, null);
    }
  }
}
