package io.breland.bbagent.server.agent.account;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityRepository;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AgentAccountResolver {
  private final AgentAccountRepository accountRepository;
  private final AgentAccountIdentityRepository identityRepository;
  private final JdbcTemplate jdbcTemplate;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public AgentAccountResolver(
      AgentAccountRepository accountRepository,
      AgentAccountIdentityRepository identityRepository,
      JdbcTemplate jdbcTemplate,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper) {
    this.accountRepository = accountRepository;
    this.identityRepository = identityRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Transactional
  public Optional<ResolvedAccount> resolveOrCreate(IncomingMessage message) {
    Optional<AccountIdentityInput> identity = identityFrom(message);
    if (identity.isEmpty()) {
      return Optional.empty();
    }
    ResolvedAccount resolved = resolveOrCreate(identity.get());
    recordMessageIdentities(message, resolved.account().getAccountId());
    return Optional.of(resolveByAccountId(resolved.account().getAccountId()));
  }

  @Transactional(readOnly = true)
  public Optional<ResolvedAccount> resolve(IncomingMessage message) {
    return identityFrom(message).flatMap(this::resolve);
  }

  @Transactional
  public Optional<ResolvedAccount> resolveOrCreate(String transport, String identifier) {
    return identityFrom(transport, identifier).map(this::resolveOrCreate);
  }

  @Transactional(readOnly = true)
  public Optional<ResolvedAccount> resolve(String transport, String identifier) {
    return identityFrom(transport, identifier).flatMap(this::resolve);
  }

  @Transactional(readOnly = true)
  public Optional<ResolvedAccount> resolveByIdentityType(String identityType, String identifier) {
    return identityFromTyped(identityType, identifier).flatMap(this::resolve);
  }

  @Transactional
  public Optional<ResolvedAccount> resolveOrCreateByIdentityType(
      String identityType, String identifier) {
    return identityFromTyped(identityType, identifier)
        .map(
            input -> {
              ResolvedAccount resolved = resolveOrCreate(input);
              return resolveByAccountId(resolved.account().getAccountId());
            });
  }

  @Transactional(readOnly = true)
  public Optional<ResolvedAccount> resolveById(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return Optional.empty();
    }
    return accountRepository.findById(accountId).map(this::toResolvedAccount);
  }

  @Transactional
  public AgentAccountEntity upsertWebsiteAccount(Jwt jwt) {
    Optional<AgentAccountEntity> appClipAccount = accountFromAppClipJwt(jwt);
    if (appClipAccount.isPresent()) {
      return appClipAccount.get();
    }
    String subject = jwtSubject(jwt);
    Instant now = Instant.now();
    AgentAccountEntity account =
        accountRepository
            .findByWebsiteSubject(subject)
            .orElseGet(
                () ->
                    emailFromJwt(jwt)
                        .flatMap(
                            email ->
                                identityFromTyped(AgentAccountIdentifiers.IMESSAGE_EMAIL, email)
                                    .flatMap(this::resolve)
                                    .map(ResolvedAccount::account))
                        .orElseGet(
                            () -> new AgentAccountEntity(UUID.randomUUID().toString(), now, now)));
    applyWebsiteClaims(account, jwt, now);
    AgentAccountEntity saved = accountRepository.save(account);
    emailFromJwt(jwt)
        .flatMap(email -> identityFromTyped(AgentAccountIdentifiers.IMESSAGE_EMAIL, email))
        .ifPresent(identity -> attachIdentityToAccount(saved.getAccountId(), identity));
    return accountRepository.findById(saved.getAccountId()).orElse(saved);
  }

  @Transactional
  public AgentAccountEntity linkWebsiteAccount(String targetAccountId, Jwt jwt) {
    if (StringUtils.isBlank(targetAccountId)) {
      throw new IllegalArgumentException("missing account id");
    }
    AgentAccountEntity target =
        accountRepository
            .findById(targetAccountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown account id"));
    String subject = jwtSubject(jwt);
    accountRepository
        .findByWebsiteSubject(subject)
        .filter(existing -> !existing.getAccountId().equals(targetAccountId))
        .ifPresent(existing -> mergeAccounts(targetAccountId, existing.getAccountId()));
    emailFromJwt(jwt)
        .flatMap(email -> identityFromTyped(AgentAccountIdentifiers.IMESSAGE_EMAIL, email))
        .flatMap(this::resolve)
        .map(ResolvedAccount::account)
        .filter(existing -> !existing.getAccountId().equals(targetAccountId))
        .ifPresent(existing -> mergeAccounts(targetAccountId, existing.getAccountId()));

    target = accountRepository.findById(targetAccountId).orElse(target);
    applyWebsiteClaims(target, jwt, Instant.now());
    AgentAccountEntity saved = accountRepository.save(target);
    emailFromJwt(jwt)
        .flatMap(email -> identityFromTyped(AgentAccountIdentifiers.IMESSAGE_EMAIL, email))
        .ifPresent(identity -> attachIdentityToAccount(saved.getAccountId(), identity));
    return accountRepository.findById(saved.getAccountId()).orElse(saved);
  }

  @Transactional
  public void recordMessageIdentities(IncomingMessage message) {
    resolveOrCreate(message);
  }

  @Transactional
  public Optional<ResolvedAccount> acceptTerms(IncomingMessage message) {
    Optional<ResolvedAccount> resolved = resolveOrCreate(message);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    AgentAccountEntity account = resolved.get().account();
    if (account.getTermsAcceptedAt() == null) {
      Instant now = Instant.now();
      account.setTermsAcceptedAt(now);
      account.setUpdatedAt(now);
      accountRepository.save(account);
    }
    return Optional.of(resolveByAccountId(account.getAccountId()));
  }

  @Transactional(readOnly = true)
  public List<AgentAccountIdentityEntity> identitiesForAccount(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return List.of();
    }
    return identityRepository.findAllByAccountIdOrderByCreatedAtAsc(accountId);
  }

  public Optional<AccountIdentityInput> identityFrom(IncomingMessage message) {
    if (message == null) {
      return Optional.empty();
    }
    return identityFrom(message.transportOrDefault(), message.sender());
  }

  public Optional<AccountIdentityInput> identityFrom(String transport, String identifier) {
    return AgentAccountIdentifiers.normalizeMessageIdentity(transport, identifier)
        .map(
            normalized ->
                new AccountIdentityInput(normalized.type(), identifier.trim(), normalized.value()));
  }

  public Optional<AccountIdentityInput> identityFromType(String identityType, String identifier) {
    return identityFromTyped(identityType, identifier);
  }

  private Optional<AccountIdentityInput> identityFromTyped(String identityType, String identifier) {
    return AgentAccountIdentifiers.normalizeByType(identityType, identifier)
        .map(
            normalized ->
                new AccountIdentityInput(normalized.type(), identifier.trim(), normalized.value()));
  }

  private Optional<ResolvedAccount> resolve(AccountIdentityInput input) {
    if (input == null) {
      return Optional.empty();
    }
    return identityRepository
        .findByIdentityTypeAndNormalizedIdentifier(
            input.identityType(), input.normalizedIdentifier())
        .flatMap(identity -> accountRepository.findById(identity.getAccountId()))
        .map(this::toResolvedAccount);
  }

  private ResolvedAccount resolveOrCreate(AccountIdentityInput input) {
    return resolve(input).orElseGet(() -> createAccount(input));
  }

  private ResolvedAccount createAccount(AccountIdentityInput input) {
    Instant now = Instant.now();
    AgentAccountEntity account =
        accountRepository.save(new AgentAccountEntity(UUID.randomUUID().toString(), now, now));
    attachIdentityToAccount(account.getAccountId(), input);
    return resolveByAccountId(account.getAccountId());
  }

  private ResolvedAccount resolveByAccountId(String accountId) {
    return accountRepository
        .findById(accountId)
        .map(this::toResolvedAccount)
        .orElseThrow(() -> new IllegalStateException("missing account " + accountId));
  }

  private ResolvedAccount toResolvedAccount(AgentAccountEntity account) {
    return new ResolvedAccount(
        account, identityRepository.findAllByAccountIdOrderByCreatedAtAsc(account.getAccountId()));
  }

  private void recordMessageIdentities(IncomingMessage message, String accountId) {
    if (message == null
        || !message.isBlueBubblesTransport()
        || StringUtils.isBlank(message.sender())
        || bbHttpClientWrapper == null) {
      return;
    }
    LinkedHashSet<String> identifiers = new LinkedHashSet<>();
    identifiers.add(message.sender());
    try {
      identifiers.addAll(bbHttpClientWrapper.getContactAddressesFor(message.sender()));
    } catch (Exception e) {
      log.debug("BlueBubbles contact lookup failed for account identity hints", e);
    }
    for (String identifier : identifiers) {
      identityFrom(IncomingMessage.TRANSPORT_BLUEBUBBLES, identifier)
          .ifPresent(identity -> attachIdentityToAccount(accountId, identity));
    }
  }

  private void attachIdentityToAccount(String accountId, AccountIdentityInput input) {
    if (StringUtils.isBlank(accountId) || input == null) {
      return;
    }
    Optional<AgentAccountIdentityEntity> existing =
        identityRepository.findByIdentityTypeAndNormalizedIdentifier(
            input.identityType(), input.normalizedIdentifier());
    if (existing.isPresent()) {
      AgentAccountIdentityEntity entity = existing.get();
      if (!accountId.equals(entity.getAccountId())) {
        mergeAccounts(accountId, entity.getAccountId());
      } else if (!input.identifier().equals(entity.getIdentifier())) {
        entity.setIdentifier(input.identifier());
        entity.setUpdatedAt(Instant.now());
        identityRepository.save(entity);
      }
      return;
    }
    Instant now = Instant.now();
    identityRepository.save(
        new AgentAccountIdentityEntity(
            UUID.randomUUID().toString(),
            accountId,
            input.identityType(),
            input.identifier(),
            input.normalizedIdentifier(),
            now,
            now));
  }

  private void mergeAccounts(String targetAccountId, String sourceAccountId) {
    if (StringUtils.isBlank(targetAccountId)
        || StringUtils.isBlank(sourceAccountId)
        || targetAccountId.equals(sourceAccountId)) {
      return;
    }
    AgentAccountEntity target =
        accountRepository
            .findById(targetAccountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown target account"));
    AgentAccountEntity source =
        accountRepository
            .findById(sourceAccountId)
            .orElseThrow(() -> new IllegalArgumentException("unknown source account"));

    for (AgentAccountIdentityEntity identity :
        identityRepository.findAllByAccountIdOrderByCreatedAtAsc(sourceAccountId)) {
      Optional<AgentAccountIdentityEntity> targetIdentity =
          identityRepository.findByIdentityTypeAndNormalizedIdentifierAndAccountId(
              identity.getIdentityType(), identity.getNormalizedIdentifier(), targetAccountId);
      if (targetIdentity.isPresent()) {
        identityRepository.delete(identity);
      } else {
        identity.setAccountId(targetAccountId);
        identity.setUpdatedAt(Instant.now());
        identityRepository.save(identity);
      }
    }

    updateAccountColumn(
        "gcal_oauth_credentials", "agent_account_id", targetAccountId, sourceAccountId);
    updateAccountColumn(
        "website_account_link_tokens", "account_id", targetAccountId, sourceAccountId);
    updateAccountColumn(
        "website_account_link_tokens", "redeemed_account_id", targetAccountId, sourceAccountId);
    updateAccountColumn("app_clip_sessions", "account_id", targetAccountId, sourceAccountId);
    updateAccountColumn(
        "payment_checkout_sessions", "account_id", targetAccountId, sourceAccountId);
    updateAccountColumn("payment_subscriptions", "account_id", targetAccountId, sourceAccountId);
    updateAccountColumn("payment_provider_events", "account_id", targetAccountId, sourceAccountId);
    mergeAccountScopedRateLimitUsage(targetAccountId, sourceAccountId);

    String sourceWebsiteSubject = source.getWebsiteSubject();
    if (StringUtils.isBlank(target.getWebsiteSubject())
        && StringUtils.isNotBlank(sourceWebsiteSubject)) {
      source.setWebsiteSubject(null);
      source.setUpdatedAt(Instant.now());
      accountRepository.saveAndFlush(source);
    }

    if (StringUtils.isBlank(target.getWebsiteSubject())) {
      target.setWebsiteSubject(sourceWebsiteSubject);
    }
    if (StringUtils.isBlank(target.getWebsiteEmail())) {
      target.setWebsiteEmail(source.getWebsiteEmail());
    }
    if (StringUtils.isBlank(target.getWebsiteDisplayName())) {
      target.setWebsiteDisplayName(source.getWebsiteDisplayName());
    }
    if (StringUtils.isBlank(target.getGlobalContactName())) {
      target.setGlobalContactName(source.getGlobalContactName());
    }
    if (!target.isPremium()) {
      target.setPremium(source.isPremium());
      target.setPremiumEntitlementSource(source.getPremiumEntitlementSource());
      target.setPremiumSubscriptionExpiresAt(source.getPremiumSubscriptionExpiresAt());
      target.setPremiumEntitlementSyncedAt(source.getPremiumEntitlementSyncedAt());
    }
    if (StringUtils.isBlank(target.getSelectedModel())) {
      target.setSelectedModel(source.getSelectedModel());
    }
    if (target.getTermsAcceptedAt() == null) {
      target.setTermsAcceptedAt(source.getTermsAcceptedAt());
    }
    if (!target.isProcessingBlocked() && source.isProcessingBlocked()) {
      target.setProcessingBlocked(true);
      target.setProcessingBlockedReason(source.getProcessingBlockedReason());
      target.setProcessingBlockedAt(source.getProcessingBlockedAt());
      target.setProcessingBlockedBy(source.getProcessingBlockedBy());
    }
    target.setUpdatedAt(Instant.now());
    accountRepository.save(target);
    accountRepository.deleteById(sourceAccountId);
  }

  private void updateAccountColumn(
      String table, String column, String targetAccountId, String sourceAccountId) {
    jdbcTemplate.update(
        "update " + table + " set " + column + " = ? where " + column + " = ?",
        targetAccountId,
        sourceAccountId);
  }

  private void mergeAccountScopedRateLimitUsage(String targetAccountId, String sourceAccountId) {
    List<Map<String, Object>> sourceRows =
        jdbcTemplate.queryForList(
            "select id, limit_key, window_start, amount from app_rate_limit_usage"
                + " where scope_type = ? and scope_key = ?",
            "account",
            sourceAccountId);
    for (Map<String, Object> sourceRow : sourceRows) {
      String sourceId = String.valueOf(sourceRow.get("id"));
      Object limitKey = sourceRow.get("limit_key");
      Object windowStart = sourceRow.get("window_start");
      Number amount = (Number) sourceRow.get("amount");
      List<Map<String, Object>> targetRows =
          jdbcTemplate.queryForList(
              "select id from app_rate_limit_usage"
                  + " where limit_key = ? and scope_type = ? and scope_key = ? and window_start = ?",
              limitKey,
              "account",
              targetAccountId,
              windowStart);
      if (targetRows.isEmpty()) {
        jdbcTemplate.update(
            "update app_rate_limit_usage set scope_key = ?, updated_at = current_timestamp"
                + " where id = ?",
            targetAccountId,
            sourceId);
        continue;
      }
      String targetId = String.valueOf(targetRows.get(0).get("id"));
      jdbcTemplate.update(
          "update app_rate_limit_usage set amount = amount + ?, updated_at = current_timestamp"
              + " where id = ?",
          amount.longValue(),
          targetId);
      jdbcTemplate.update("delete from app_rate_limit_usage where id = ?", sourceId);
    }
  }

  private void applyWebsiteClaims(AgentAccountEntity account, Jwt jwt, Instant now) {
    account.setWebsiteSubject(jwtSubject(jwt));
    account.setWebsiteEmail(jwt.getClaimAsString("email"));
    account.setWebsiteDisplayName(resolveDisplayName(jwt));
    account.setUpdatedAt(now);
    if (account.getCreatedAt() == null) {
      account.setCreatedAt(now);
    }
  }

  private String jwtSubject(Jwt jwt) {
    if (jwt == null || StringUtils.isBlank(jwt.getSubject())) {
      throw new IllegalArgumentException("missing account subject");
    }
    return jwt.getSubject();
  }

  private Optional<AgentAccountEntity> accountFromAppClipJwt(Jwt jwt) {
    String accountId =
        jwt == null ? null : jwt.getClaimAsString(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM);
    if (StringUtils.isBlank(accountId)) {
      return Optional.empty();
    }
    return accountRepository.findById(accountId.trim());
  }

  private Optional<String> emailFromJwt(Jwt jwt) {
    return Optional.ofNullable(jwt == null ? null : jwt.getClaimAsString("email"))
        .map(String::trim)
        .filter(StringUtils::isNotBlank);
  }

  private String resolveDisplayName(Jwt jwt) {
    String name = jwt.getClaimAsString("name");
    if (StringUtils.isNotBlank(name)) {
      return name;
    }
    String given = jwt.getClaimAsString("given_name");
    String family = jwt.getClaimAsString("family_name");
    String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
    if (StringUtils.isNotBlank(joined)) {
      return joined;
    }
    return null;
  }

  public record AccountIdentityInput(
      String identityType, String identifier, String normalizedIdentifier) {}

  public record ResolvedAccount(
      AgentAccountEntity account, List<AgentAccountIdentityEntity> identities) {}
}
