package io.breland.bbagent.server.website;

import io.breland.bbagent.generated.model.WebsiteAccountLink;
import io.breland.bbagent.generated.model.WebsiteAccountProfile;
import io.breland.bbagent.generated.model.WebsiteAccountRedeemLinkResponse;
import io.breland.bbagent.generated.model.WebsiteAccountResponse;
import io.breland.bbagent.generated.model.WebsiteCalendarAccountSummary;
import io.breland.bbagent.generated.model.WebsiteIntegrationSummary;
import io.breland.bbagent.generated.model.WebsiteLinkedAccountsResponse;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WebsiteAccountService {
  private final WebsiteAccountRepository accountRepository;
  private final WebsiteAccountLinkTokenRepository tokenRepository;
  private final WebsiteAccountSenderLinkRepository linkRepository;
  private final GcalClient gcalClient;
  private final CoderMcpClient coderMcpClient;
  private final ModelAccessService modelAccessService;
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
      @Value("${website.base-url:http://localhost:8080}") String websiteBaseUrl,
      @Value("${website.account-link-token-ttl-minutes:30}") long linkTokenTtlMinutes) {
    this.accountRepository = accountRepository;
    this.tokenRepository = tokenRepository;
    this.linkRepository = linkRepository;
    this.gcalClient = gcalClient;
    this.coderMcpClient = coderMcpClient;
    this.modelAccessService = modelAccessService;
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
    LinkIdentity identity = resolveIdentity(message);
    if (identity.accountBase().isBlank()) {
      throw new IllegalArgumentException("missing iMessage identity");
    }

    String token = newToken();
    String tokenHash = hashToken(token);
    Instant now = Instant.now();
    Instant expiresAt = now.plus(linkTokenTtl);
    WebsiteAccountLinkTokenEntity entity =
        new WebsiteAccountLinkTokenEntity(
            tokenHash,
            identity.accountBase(),
            identity.coderAccountBase(),
            identity.gcalAccountBase(),
            message.chatGuid(),
            message.sender(),
            message.service(),
            message.isGroup(),
            message.messageGuid(),
            expiresAt,
            now,
            now);
    tokenRepository.save(entity);
    String url =
        UriComponentsBuilder.fromUriString(websiteBaseUrl)
            .path("/account/link")
            .queryParam("token", token)
            .build()
            .toUriString();
    return new CreatedLinkToken(url, expiresAt, identity.accountBase());
  }

  @Transactional(readOnly = true)
  public SenderLinkStatus getLinkStatus(IncomingMessage message) {
    if (message == null) {
      return SenderLinkStatus.empty();
    }
    return getLinkStatus(message.sender(), message.chatGuid());
  }

  @Transactional(readOnly = true)
  public SenderLinkStatus getLinkStatus(String sender, String chatGuid) {
    LinkIdentity identity = resolveIdentity(sender, chatGuid);
    if (identity.accountBase().isBlank()) {
      return SenderLinkStatus.empty();
    }
    List<WebsiteAccountSenderLinkEntity> accountLinks =
        linkRepository.findAllByAccountBaseOrderByCreatedAtDesc(identity.accountBase());
    List<WebsiteAccountSenderLinkEntity> exactLinks =
        linkRepository
            .findAllByAccountBaseAndCoderAccountBaseAndGcalAccountBaseOrderByCreatedAtDesc(
                identity.accountBase(), identity.coderAccountBase(), identity.gcalAccountBase());
    Instant latestLinkedAt =
        exactLinks.stream()
            .findFirst()
            .or(() -> accountLinks.stream().findFirst())
            .map(WebsiteAccountSenderLinkEntity::getCreatedAt)
            .orElse(null);
    return new SenderLinkStatus(
        identity.accountBase(),
        identity.coderAccountBase(),
        identity.gcalAccountBase(),
        !accountLinks.isEmpty(),
        !exactLinks.isEmpty(),
        accountLinks.size(),
        exactLinks.size(),
        modelAccessService.toWebsiteSummary(identity.accountBase()),
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
    if (tokenEntity.getExpiresAt().isBefore(now)) {
      throw new ResponseStatusException(HttpStatus.GONE, "Link expired");
    }
    if (tokenEntity.getRedeemedAccountSubject() != null
        && !tokenEntity.getRedeemedAccountSubject().equals(account.getKeycloakSubject())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Link already redeemed by another account");
    }

    boolean tokenAlreadyRedeemed = tokenEntity.getRedeemedAccountSubject() != null;
    Optional<WebsiteAccountSenderLinkEntity> existingLink =
        linkRepository.findByAccountSubjectAndCoderAccountBaseAndGcalAccountBase(
            account.getKeycloakSubject(),
            tokenEntity.getCoderAccountBase(),
            tokenEntity.getGcalAccountBase());
    if (tokenAlreadyRedeemed && existingLink.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Link token already consumed");
    }
    boolean alreadyLinked = existingLink.isPresent();
    WebsiteAccountSenderLinkEntity link =
        existingLink.orElseGet(() -> newSenderLink(account, tokenEntity, now));
    link.setUpdatedAt(now);
    link = linkRepository.save(link);

    tokenEntity.setRedeemedAt(
        tokenEntity.getRedeemedAt() == null ? now : tokenEntity.getRedeemedAt());
    tokenEntity.setRedeemedAccountSubject(account.getKeycloakSubject());
    tokenEntity.setUpdatedAt(now);
    tokenRepository.save(tokenEntity);

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
        gcalClient.listAccountsFor(link.getGcalAccountBase()).stream()
            .map(accountId -> new WebsiteCalendarAccountSummary().accountId(accountId))
            .toList();
    return new WebsiteIntegrationSummary()
        .link(toLink(link))
        .coderLinked(coderMcpClient != null && coderMcpClient.isLinked(link.getCoderAccountBase()))
        .gcalAccounts(gcalAccounts)
        .modelAccess(modelAccessService.toWebsiteSummary(link.getAccountBase()));
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

  private LinkIdentity resolveIdentity(IncomingMessage message) {
    return resolveIdentity(message.sender(), message.chatGuid());
  }

  private LinkIdentity resolveIdentity(String rawSender, String rawChatGuid) {
    String sender = clean(rawSender);
    String chatGuid = clean(rawChatGuid);
    String coderAccountBase = firstNonBlank(sender, chatGuid);
    String gcalAccountBase =
        sender != null && chatGuid != null
            ? chatGuid + "|" + sender
            : firstNonBlank(sender, chatGuid);
    String accountBase = firstNonBlank(sender, chatGuid);
    return new LinkIdentity(
        accountBase == null ? "" : accountBase,
        coderAccountBase == null ? "" : coderAccountBase,
        gcalAccountBase == null ? "" : gcalAccountBase);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String clean(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String newToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private String stripTrailingSlash(String value) {
    String base = value == null || value.isBlank() ? "http://localhost:8080" : value.trim();
    URI.create(base);
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
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

  private record LinkIdentity(
      String accountBase, String coderAccountBase, String gcalAccountBase) {}
}
