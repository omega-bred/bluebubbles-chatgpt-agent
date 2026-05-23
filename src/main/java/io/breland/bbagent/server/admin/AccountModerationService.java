package io.breland.bbagent.server.admin;

import io.breland.bbagent.generated.model.AdminAccountBlockListResponse;
import io.breland.bbagent.generated.model.AdminAccountBlockRequest;
import io.breland.bbagent.generated.model.AdminAccountBlockResponse;
import io.breland.bbagent.generated.model.AdminBlockedAccountItem;
import io.breland.bbagent.generated.model.WebsiteAccountIdentity;
import io.breland.bbagent.server.agent.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountModerationService {
  private final AgentAccountRepository accountRepository;
  private final AgentAccountResolver accountResolver;

  public AccountModerationService(
      AgentAccountRepository accountRepository, AgentAccountResolver accountResolver) {
    this.accountRepository = accountRepository;
    this.accountResolver = accountResolver;
  }

  @Transactional(readOnly = true)
  public AdminAccountBlockListResponse listBlocked(Integer limit) {
    int resolvedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
    return new AdminAccountBlockListResponse()
        .accounts(
            accountRepository
                .findAllByProcessingBlockedTrueOrderByProcessingBlockedAtDesc(
                    PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toItem)
                .toList());
  }

  @Transactional
  public AdminAccountBlockResponse block(AdminAccountBlockRequest request, Jwt adminJwt) {
    AgentAccountEntity account = resolveTarget(request, true);
    Instant now = Instant.now();
    account.setProcessingBlocked(true);
    account.setProcessingBlockedReason(StringUtils.trimToNull(request.getReason()));
    account.setProcessingBlockedAt(now);
    account.setProcessingBlockedBy(adminIdentity(adminJwt));
    account.setUpdatedAt(now);
    return new AdminAccountBlockResponse()
        .status("blocked")
        .account(toItem(accountRepository.save(account)));
  }

  @Transactional
  public AdminAccountBlockResponse unblock(AdminAccountBlockRequest request, Jwt adminJwt) {
    AgentAccountEntity account = resolveTarget(request, false);
    Instant now = Instant.now();
    account.setProcessingBlocked(false);
    account.setProcessingBlockedReason(null);
    account.setProcessingBlockedAt(null);
    account.setProcessingBlockedBy(adminIdentity(adminJwt));
    account.setUpdatedAt(now);
    return new AdminAccountBlockResponse()
        .status("unblocked")
        .account(toItem(accountRepository.save(account)));
  }

  private AgentAccountEntity resolveTarget(
      AdminAccountBlockRequest request, boolean createIdentity) {
    if (request == null || StringUtils.isBlank(request.getTarget())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account target is required");
    }
    String target = request.getTarget().trim();
    String targetType =
        request.getTargetType() == null ? "account_id" : request.getTargetType().getValue();
    return switch (targetType) {
      case "account_id" ->
          accountRepository
              .findById(target)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
      case "website_subject" ->
          accountRepository
              .findByWebsiteSubject(target)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
      case "website_email" ->
          accountRepository
              .findByWebsiteEmailIgnoreCase(target)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
      case AgentAccountIdentifiers.IMESSAGE_EMAIL,
          AgentAccountIdentifiers.IMESSAGE_PHONE,
          AgentAccountIdentifiers.LXMF_ADDRESS ->
          resolveTransportIdentity(targetType, target, createIdentity);
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported target type");
    };
  }

  private AgentAccountEntity resolveTransportIdentity(
      String targetType, String target, boolean createIdentity) {
    return (createIdentity
            ? accountResolver.resolveOrCreateByIdentityType(targetType, target)
            : accountResolver.resolveByIdentityType(targetType, target))
        .map(AgentAccountResolver.ResolvedAccount::account)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
  }

  private AdminBlockedAccountItem toItem(AgentAccountEntity account) {
    return new AdminBlockedAccountItem()
        .accountId(account.getAccountId())
        .accountBucket(accountBucket(account.getAccountId()))
        .blocked(account.isProcessingBlocked())
        .reason(account.getProcessingBlockedReason())
        .blockedAt(offset(account.getProcessingBlockedAt()))
        .blockedBy(account.getProcessingBlockedBy())
        .websiteEmail(account.getWebsiteEmail())
        .websiteSubject(account.getWebsiteSubject())
        .identities(
            accountResolver.identitiesForAccount(account.getAccountId()).stream()
                .map(
                    identity ->
                        new WebsiteAccountIdentity()
                            .type(
                                WebsiteAccountIdentity.TypeEnum.fromValue(
                                    identity.getIdentityType()))
                            .identifier(identity.getIdentifier())
                            .normalizedIdentifier(identity.getNormalizedIdentifier()))
                .toList());
  }

  private String adminIdentity(Jwt jwt) {
    return StringUtils.firstNonBlank(
        jwt == null ? null : jwt.getClaimAsString("email"),
        jwt == null ? null : jwt.getClaimAsString("preferred_username"),
        jwt == null ? null : jwt.getSubject(),
        "admin");
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String accountBucket(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return "unknown";
    }
    return accountId.substring(0, Math.min(8, accountId.length()));
  }
}
