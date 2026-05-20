package io.breland.bbagent.server.subscriptions;

import io.breland.bbagent.generated.model.AdminPremiumGrantRequest;
import io.breland.bbagent.generated.model.AdminSubscriptionActionRequest;
import io.breland.bbagent.generated.model.AdminSubscriptionActionResponse;
import io.breland.bbagent.generated.model.AdminSubscriptionItem;
import io.breland.bbagent.generated.model.AdminSubscriptionListResponse;
import io.breland.bbagent.generated.model.AdminSubscriptionStats;
import io.breland.bbagent.generated.model.SubscriptionCheckoutRequest;
import io.breland.bbagent.generated.model.SubscriptionCheckoutResponse;
import io.breland.bbagent.generated.model.SubscriptionPlan;
import io.breland.bbagent.generated.model.SubscriptionPlansResponse;
import io.breland.bbagent.generated.model.SubscriptionPortalResponse;
import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.generated.model.SubscriptionSummaryResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentCheckoutSessionEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentCheckoutSessionRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubscriptionService {
  private static final String SOURCE_NONE = "none";
  private static final String SOURCE_MANUAL = "manual";
  private static final String SOURCE_SUBSCRIPTION = "subscription";

  private final SubscriptionProperties properties;
  private final SubscriptionProviderRegistry providerRegistry;
  private final AgentAccountResolver accountResolver;
  private final AgentAccountRepository accountRepository;
  private final PaymentCheckoutSessionRepository checkoutRepository;
  private final PaymentSubscriptionRepository subscriptionRepository;
  private final PaymentProviderEventRepository eventRepository;

  public SubscriptionService(
      SubscriptionProperties properties,
      SubscriptionProviderRegistry providerRegistry,
      AgentAccountResolver accountResolver,
      AgentAccountRepository accountRepository,
      PaymentCheckoutSessionRepository checkoutRepository,
      PaymentSubscriptionRepository subscriptionRepository,
      PaymentProviderEventRepository eventRepository) {
    this.properties = properties;
    this.providerRegistry = providerRegistry;
    this.accountResolver = accountResolver;
    this.accountRepository = accountRepository;
    this.checkoutRepository = checkoutRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.eventRepository = eventRepository;
  }

  public SubscriptionPlansResponse listPlans() {
    return new SubscriptionPlansResponse()
        .defaultProvider(properties.getDefaultProvider())
        .plans(properties.isEnabled() ? activePlans() : List.of());
  }

  @Transactional
  public SubscriptionSummaryResponse getAccountSubscription(Jwt jwt) {
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    recomputePremium(account.getAccountId());
    return summary(account.getAccountId());
  }

  @Transactional
  public SubscriptionCheckoutResponse createCheckout(Jwt jwt, SubscriptionCheckoutRequest request) {
    ensureSubscriptionsEnabled();
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    String providerKey =
        firstNonBlank(
            request == null ? null : request.getProvider(), properties.getDefaultProvider());
    SubscriptionProvider provider = requireProvider(providerKey);
    providerKey = provider.providerKey();
    SubscriptionProperties.Plan plan = requirePlan(request == null ? null : request.getPlanKey());
    SubscriptionProperties.ProviderPlan providerPlan = requireProviderPlan(plan, providerKey);
    Instant now = Instant.now();
    Instant expiresAt = now.plusSeconds(Math.max(1, properties.getCheckoutDurationMinutes()) * 60L);
    PaymentCheckoutSessionEntity checkout =
        checkoutRepository.save(
            new PaymentCheckoutSessionEntity(
                UUID.randomUUID().toString(),
                account.getAccountId(),
                providerKey,
                plan.getKey(),
                SubscriptionStatuses.CHECKOUT_CREATED,
                expiresAt,
                now,
                now));

    SubscriptionProvider.ProviderCheckoutSession providerCheckout;
    try {
      providerCheckout =
          provider.createCheckout(
              new SubscriptionProvider.CheckoutRequest(
                  account.getAccountId(),
                  StringUtils.trimToNull(account.getWebsiteEmail()),
                  checkout.getCheckoutSessionId(),
                  plan,
                  providerPlan,
                  properties.getReturnUrl(),
                  properties.getCheckoutDurationMinutes()));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider request failed", e);
    }
    if (providerCheckout == null || StringUtils.isBlank(providerCheckout.checkoutUrl())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Provider did not return checkout URL");
    }
    checkout.setProviderCheckoutId(providerCheckout.providerCheckoutId());
    checkout.setCheckoutUrl(providerCheckout.checkoutUrl());
    checkout.setExpiresAt(
        providerCheckout.expiresAt() == null ? expiresAt : providerCheckout.expiresAt());
    checkout.setProviderPayload(providerCheckout.rawPayload());
    checkout.setUpdatedAt(Instant.now());
    checkoutRepository.save(checkout);

    return new SubscriptionCheckoutResponse()
        .checkoutSessionId(checkout.getCheckoutSessionId())
        .provider(providerKey)
        .planKey(plan.getKey())
        .checkoutUrl(checkout.getCheckoutUrl())
        .expiresAt(offset(checkout.getExpiresAt()));
  }

  @Transactional
  public SubscriptionPortalResponse createPortal(Jwt jwt) {
    ensureSubscriptionsEnabled();
    AgentAccountEntity account = accountResolver.upsertWebsiteAccount(jwt);
    PaymentSubscriptionEntity subscription =
        subscriptionRepository
            .findFirstByAccountIdOrderByUpdatedAtDesc(account.getAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription found"));
    SubscriptionProvider provider = requireProvider(subscription.getProvider());
    SubscriptionProperties.Plan plan = requirePlan(subscription.getPlanKey());
    SubscriptionProperties.ProviderPlan providerPlan =
        requireProviderPlan(plan, provider.providerKey());
    SubscriptionProvider.ProviderPortalSession portal;
    try {
      portal =
          provider.createPortalSession(
              new SubscriptionProvider.PortalRequest(
                  account.getAccountId(),
                  account.getWebsiteEmail(),
                  plan,
                  providerPlan,
                  provider.customerSelector(
                      account.getAccountId(), subscription.getProviderCustomerSelector()),
                  properties.getReturnUrl()));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider request failed", e);
    }
    if (portal == null || StringUtils.isBlank(portal.portalUrl())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Provider did not return portal URL");
    }
    subscription.setManagementUrl(
        firstNonBlank(portal.portalUrl(), subscription.getManagementUrl()));
    subscription.setUpdatedAt(Instant.now());
    subscriptionRepository.save(subscription);
    return new SubscriptionPortalResponse()
        .provider(subscription.getProvider())
        .portalUrl(portal.portalUrl());
  }

  @Transactional
  public SubscriptionProviderWebhookResponse receiveWebhook(
      String providerKey, HttpHeaders headers, byte[] body) {
    String normalizedProviderKey = firstNonBlank(providerKey);
    SubscriptionProvider provider = requireProvider(normalizedProviderKey);
    normalizedProviderKey = provider.providerKey();
    SubscriptionProvider.ProviderWebhookEvent webhookEvent;
    try {
      webhookEvent = provider.verifyAndParseWebhook(headers, body);
    } catch (WebhookVerificationException e) {
      return new SubscriptionProviderWebhookResponse()
          .status("unauthorized")
          .message(e.getMessage());
    }

    PaymentProviderEventEntity event =
        eventRepository
            .findByProviderAndProviderEventId(normalizedProviderKey, webhookEvent.providerEventId())
            .orElse(null);
    if (event != null && !SubscriptionStatuses.EVENT_FAILED.equals(event.getStatus())) {
      return new SubscriptionProviderWebhookResponse()
          .status(SubscriptionStatuses.EVENT_DUPLICATE)
          .message("Webhook event already processed");
    }

    if (event == null) {
      event =
          new PaymentProviderEventEntity(
              UUID.randomUUID().toString(),
              normalizedProviderKey,
              webhookEvent.providerEventId(),
              webhookEvent.eventType(),
              SubscriptionStatuses.EVENT_RECEIVED,
              webhookEvent.rawPayload(),
              Instant.now());
    } else {
      event.setStatus(SubscriptionStatuses.EVENT_RECEIVED);
      event.setErrorMessage(null);
      event.setProcessedAt(null);
    }
    event.setAccountId(webhookEvent.accountId());
    event.setCheckoutSessionId(webhookEvent.checkoutSessionId());
    event.setProviderSubscriptionId(webhookEvent.providerSubscriptionId());
    try {
      eventRepository.saveAndFlush(event);
    } catch (DataIntegrityViolationException duplicate) {
      return new SubscriptionProviderWebhookResponse()
          .status(SubscriptionStatuses.EVENT_DUPLICATE)
          .message("Webhook event already processed");
    }

    try {
      ProcessedWebhook processed = processProviderEvent(normalizedProviderKey, webhookEvent);
      event.setStatus(
          processed.processed()
              ? SubscriptionStatuses.EVENT_PROCESSED
              : SubscriptionStatuses.EVENT_IGNORED);
      event.setAccountId(firstNonBlank(event.getAccountId(), processed.accountId()));
      event.setCheckoutSessionId(
          firstNonBlank(event.getCheckoutSessionId(), processed.checkoutSessionId()));
      event.setSubscriptionId(processed.subscriptionId());
      event.setProcessedAt(Instant.now());
      eventRepository.save(event);
      return new SubscriptionProviderWebhookResponse()
          .status(event.getStatus())
          .message(processed.message())
          .accountId(event.getAccountId())
          .subscriptionId(event.getSubscriptionId());
    } catch (Exception e) {
      event.setStatus(SubscriptionStatuses.EVENT_FAILED);
      event.setErrorMessage(e.getMessage());
      event.setProcessedAt(Instant.now());
      eventRepository.save(event);
      return new SubscriptionProviderWebhookResponse()
          .status(SubscriptionStatuses.EVENT_FAILED)
          .message("Webhook processing failed")
          .accountId(event.getAccountId())
          .subscriptionId(event.getSubscriptionId());
    }
  }

  @Transactional(readOnly = true)
  public AdminSubscriptionListResponse adminListSubscriptions(int limit) {
    int resolvedLimit = Math.max(1, Math.min(limit, 500));
    List<PaymentSubscriptionEntity> subscriptions =
        subscriptionRepository.findByOrderByUpdatedAtDesc(PageRequest.of(0, resolvedLimit));
    List<PaymentSubscriptionEntity> all = subscriptionRepository.findAll();
    return new AdminSubscriptionListResponse()
        .generatedAt(offset(Instant.now()))
        .stats(adminStats(all))
        .subscriptions(subscriptions.stream().map(this::toAdminItem).toList());
  }

  @Transactional
  public AdminSubscriptionActionResponse adminSync(AdminSubscriptionActionRequest request) {
    PaymentSubscriptionEntity subscription = requireSubscription(request);
    PaymentSubscriptionEntity updated = syncSubscription(subscription);
    return actionResponse("synced", updated);
  }

  @Transactional
  public AdminSubscriptionActionResponse adminSuspend(AdminSubscriptionActionRequest request) {
    PaymentSubscriptionEntity subscription = requireSubscription(request);
    SubscriptionProvider provider = requireProvider(subscription.getProvider());
    SubscriptionProvider.ProviderSubscription providerSubscription =
        provider.suspendSubscription(
            lookup(subscription),
            firstNonBlank(
                request == null ? null : request.getReason(), "Suspended by bbagent admin"));
    PaymentSubscriptionEntity updated =
        applyProviderSubscription(subscription, providerSubscription, Instant.now());
    recomputePremium(updated.getAccountId());
    return actionResponse("suspended", updated);
  }

  @Transactional
  public AdminSubscriptionActionResponse adminUnsuspend(AdminSubscriptionActionRequest request) {
    PaymentSubscriptionEntity subscription = requireSubscription(request);
    SubscriptionProvider provider = requireProvider(subscription.getProvider());
    SubscriptionProvider.ProviderSubscription providerSubscription =
        provider.unsuspendSubscription(lookup(subscription));
    PaymentSubscriptionEntity updated =
        applyProviderSubscription(subscription, providerSubscription, Instant.now());
    recomputePremium(updated.getAccountId());
    return actionResponse("unsuspended", updated);
  }

  @Transactional
  public AdminSubscriptionActionResponse adminGrantPremium(AdminPremiumGrantRequest request) {
    if (request == null || StringUtils.isBlank(request.getAccountId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing account id");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(request.getAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    account.setPremium(true);
    account.setPremiumEntitlementSource(SOURCE_MANUAL);
    account.setPremiumEntitlementSyncedAt(Instant.now());
    account.setUpdatedAt(Instant.now());
    accountRepository.save(account);
    return new AdminSubscriptionActionResponse()
        .status("manual_granted")
        .accountId(account.getAccountId());
  }

  @Transactional
  public AdminSubscriptionActionResponse adminRevokeManualPremium(
      AdminPremiumGrantRequest request) {
    if (request == null || StringUtils.isBlank(request.getAccountId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing account id");
    }
    AgentAccountEntity account =
        accountRepository
            .findById(request.getAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (SOURCE_MANUAL.equals(account.getPremiumEntitlementSource())) {
      account.setPremiumEntitlementSource(SOURCE_NONE);
      accountRepository.save(account);
    }
    recomputePremium(account.getAccountId());
    return new AdminSubscriptionActionResponse()
        .status("manual_revoked")
        .accountId(account.getAccountId());
  }

  private ProcessedWebhook processProviderEvent(
      String providerKey, SubscriptionProvider.ProviderWebhookEvent webhookEvent) {
    PaymentCheckoutSessionEntity checkout = findCheckout(providerKey, webhookEvent).orElse(null);
    String accountId =
        firstNonBlank(webhookEvent.accountId(), checkout == null ? null : checkout.getAccountId());
    if (StringUtils.isBlank(accountId)) {
      return new ProcessedWebhook(
          false, null, null, null, "Webhook did not include a known account");
    }
    SubscriptionProperties.Plan plan =
        requirePlan(checkout == null ? properties.defaultPlanKey() : checkout.getPlanKey());
    SubscriptionProperties.ProviderPlan providerPlan = requireProviderPlan(plan, providerKey);
    SubscriptionProvider provider = requireProvider(providerKey);
    String selector = provider.customerSelector(accountId, webhookEvent.customerSelector());
    SubscriptionProvider.ProviderSubscription providerSubscription =
        provider.fetchSubscription(
            new SubscriptionProvider.SubscriptionLookup(
                accountId, plan, providerPlan, selector, webhookEvent.providerSubscriptionId()));
    PaymentSubscriptionEntity subscription =
        upsertProviderSubscription(
            accountId, providerKey, plan, providerPlan, checkout, providerSubscription);
    if (checkout != null) {
      checkout.setStatus(SubscriptionStatuses.CHECKOUT_COMPLETED);
      checkout.setUpdatedAt(Instant.now());
      checkoutRepository.save(checkout);
    }
    recomputePremium(accountId);
    return new ProcessedWebhook(
        true,
        accountId,
        checkout == null ? null : checkout.getCheckoutSessionId(),
        subscription.getSubscriptionId(),
        "Webhook processed");
  }

  private Optional<PaymentCheckoutSessionEntity> findCheckout(
      String providerKey, SubscriptionProvider.ProviderWebhookEvent webhookEvent) {
    if (StringUtils.isNotBlank(webhookEvent.checkoutSessionId())) {
      Optional<PaymentCheckoutSessionEntity> checkout =
          checkoutRepository.findById(webhookEvent.checkoutSessionId());
      if (checkout.isPresent()) {
        return checkout;
      }
    }
    if (StringUtils.isNotBlank(webhookEvent.providerCheckoutId())) {
      return checkoutRepository.findByProviderAndProviderCheckoutId(
          providerKey, webhookEvent.providerCheckoutId());
    }
    return Optional.empty();
  }

  private PaymentSubscriptionEntity syncSubscription(PaymentSubscriptionEntity subscription) {
    SubscriptionProvider provider = requireProvider(subscription.getProvider());
    SubscriptionProvider.ProviderSubscription providerSubscription =
        provider.fetchSubscription(lookup(subscription));
    PaymentSubscriptionEntity updated =
        applyProviderSubscription(subscription, providerSubscription, Instant.now());
    recomputePremium(updated.getAccountId());
    return updated;
  }

  private PaymentSubscriptionEntity upsertProviderSubscription(
      String accountId,
      String providerKey,
      SubscriptionProperties.Plan plan,
      SubscriptionProperties.ProviderPlan providerPlan,
      PaymentCheckoutSessionEntity checkout,
      SubscriptionProvider.ProviderSubscription providerSubscription) {
    PaymentSubscriptionEntity subscription =
        findExistingSubscription(providerKey, providerSubscription)
            .orElseGet(
                () ->
                    new PaymentSubscriptionEntity(
                        UUID.randomUUID().toString(),
                        accountId,
                        providerKey,
                        plan.getKey(),
                        providerSubscription.customerSelector(),
                        SubscriptionStatuses.SUBSCRIPTION_PENDING,
                        Instant.now(),
                        Instant.now()));
    subscription.setAccountId(accountId);
    subscription.setProvider(providerKey);
    subscription.setPlanKey(plan.getKey());
    subscription.setProviderOfferingId(providerPlan.getOfferingId());
    subscription.setProviderPlanId(providerPlan.getPlanId());
    if (checkout != null) {
      subscription.setCheckoutSessionId(checkout.getCheckoutSessionId());
    }
    return applyProviderSubscription(subscription, providerSubscription, Instant.now());
  }

  private Optional<PaymentSubscriptionEntity> findExistingSubscription(
      String providerKey, SubscriptionProvider.ProviderSubscription providerSubscription) {
    if (StringUtils.isNotBlank(providerSubscription.providerSubscriptionId())) {
      Optional<PaymentSubscriptionEntity> byProviderId =
          subscriptionRepository.findByProviderAndProviderSubscriptionId(
              providerKey, providerSubscription.providerSubscriptionId());
      if (byProviderId.isPresent()) {
        return byProviderId;
      }
    }
    if (StringUtils.isNotBlank(providerSubscription.customerSelector())) {
      return subscriptionRepository.findByProviderAndProviderCustomerSelector(
          providerKey, providerSubscription.customerSelector());
    }
    return Optional.empty();
  }

  private PaymentSubscriptionEntity applyProviderSubscription(
      PaymentSubscriptionEntity subscription,
      SubscriptionProvider.ProviderSubscription providerSubscription,
      Instant now) {
    subscription.setProviderSubscriptionId(providerSubscription.providerSubscriptionId());
    subscription.setProviderCustomerId(providerSubscription.providerCustomerId());
    subscription.setProviderCustomerSelector(providerSubscription.customerSelector());
    subscription.setProviderStatus(providerSubscription.providerStatus());
    subscription.setStatus(providerSubscription.normalizedStatus());
    subscription.setCurrentPeriodStart(providerSubscription.currentPeriodStart());
    subscription.setCurrentPeriodEnd(providerSubscription.currentPeriodEnd());
    subscription.setTrialEnd(providerSubscription.trialEnd());
    subscription.setGracePeriodEnd(providerSubscription.gracePeriodEnd());
    subscription.setCanceledAt(providerSubscription.canceledAt());
    subscription.setCancelAtPeriodEnd(providerSubscription.cancelAtPeriodEnd());
    subscription.setManagementUrl(providerSubscription.managementUrl());
    subscription.setRawPayload(providerSubscription.rawPayload());
    subscription.setUpdatedAt(now);
    subscription.setLastSyncedAt(now);
    if (subscription.getCreatedAt() == null) {
      subscription.setCreatedAt(now);
    }
    return subscriptionRepository.save(subscription);
  }

  private void recomputePremium(String accountId) {
    AgentAccountEntity account = accountRepository.findById(accountId).orElse(null);
    if (account == null) {
      return;
    }
    boolean manual =
        account.isPremium() && SOURCE_MANUAL.equals(account.getPremiumEntitlementSource());
    Instant subscriptionUntil = premiumSubscriptionUntil(accountId);
    boolean subscriptionActive =
        subscriptionUntil != null && subscriptionUntil.isAfter(Instant.now());
    account.setPremiumSubscriptionExpiresAt(subscriptionUntil);
    account.setPremium(manual || subscriptionActive);
    if (manual) {
      account.setPremiumEntitlementSource(SOURCE_MANUAL);
    } else if (subscriptionActive) {
      account.setPremiumEntitlementSource(SOURCE_SUBSCRIPTION);
    } else {
      account.setPremiumEntitlementSource(SOURCE_NONE);
    }
    account.setPremiumEntitlementSyncedAt(Instant.now());
    account.setUpdatedAt(Instant.now());
    accountRepository.save(account);
  }

  private Instant premiumSubscriptionUntil(String accountId) {
    return subscriptionRepository
        .findAllByAccountIdAndStatusIn(accountId, SubscriptionStatuses.PREMIUM_ACCESS_STATUSES)
        .stream()
        .map(this::accessUntil)
        .filter(instant -> instant != null && instant.isAfter(Instant.now()))
        .max(Instant::compareTo)
        .orElse(null);
  }

  private Instant accessUntil(PaymentSubscriptionEntity subscription) {
    if (SubscriptionStatuses.SUBSCRIPTION_TRIALING.equals(subscription.getStatus())) {
      return firstInstant(
          subscription.getTrialEnd(),
          subscription.getCurrentPeriodEnd(),
          subscription.getGracePeriodEnd());
    }
    if (SubscriptionStatuses.SUBSCRIPTION_GRACE.equals(subscription.getStatus())) {
      return firstInstant(subscription.getGracePeriodEnd(), subscription.getCurrentPeriodEnd());
    }
    return firstInstant(subscription.getCurrentPeriodEnd(), subscription.getGracePeriodEnd());
  }

  private SubscriptionSummaryResponse summary(String accountId) {
    AgentAccountEntity account = accountRepository.findById(accountId).orElseThrow();
    List<PaymentSubscriptionEntity> subscriptions =
        subscriptionRepository.findAllByAccountIdOrderByUpdatedAtDesc(accountId);
    List<PaymentCheckoutSessionEntity> checkouts =
        checkoutRepository.findAllByAccountIdOrderByCreatedAtDesc(accountId);
    PaymentCheckoutSessionEntity latestCheckout = checkouts.isEmpty() ? null : checkouts.get(0);
    return new SubscriptionSummaryResponse()
        .accountId(accountId)
        .isPremium(account.isPremium())
        .entitlementSource(account.getPremiumEntitlementSource())
        .premiumUntil(offset(account.getPremiumSubscriptionExpiresAt()))
        .plans(properties.isEnabled() ? activePlans() : List.of())
        .subscriptions(subscriptions.stream().map(this::toAdminItem).toList())
        .latestCheckout(latestCheckout == null ? null : latestCheckout.getCheckoutUrl());
  }

  private SubscriptionPlan toPlan(SubscriptionProperties.Plan plan, String providerKey) {
    return new SubscriptionPlan()
        .key(plan.getKey())
        .displayName(plan.getDisplayName())
        .description(plan.getDescription())
        .priceAmount(
            plan.getPriceAmount() == null
                ? ""
                : plan.getPriceAmount().stripTrailingZeros().toPlainString())
        .currency(plan.getCurrency())
        .billingInterval(plan.getBillingInterval())
        .provider(providerKey)
        .active(plan.isActive());
  }

  private AdminSubscriptionStats adminStats(List<PaymentSubscriptionEntity> subscriptions) {
    long active = subscriptions.stream().filter(this::hasPremiumAccess).count();
    long pastDue =
        subscriptions.stream()
            .filter(
                subscription ->
                    SubscriptionStatuses.SUBSCRIPTION_PAST_DUE.equals(subscription.getStatus()))
            .count();
    long canceled =
        subscriptions.stream()
            .filter(
                subscription ->
                    SubscriptionStatuses.SUBSCRIPTION_CANCELED.equals(subscription.getStatus())
                        || SubscriptionStatuses.SUBSCRIPTION_EXPIRED.equals(
                            subscription.getStatus())
                        || SubscriptionStatuses.SUBSCRIPTION_SUSPENDED.equals(
                            subscription.getStatus()))
            .count();
    BigDecimal monthly =
        subscriptions.stream()
            .filter(this::hasPremiumAccess)
            .map(this::monthlyAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    String currency =
        properties.getPlans().stream()
            .findFirst()
            .map(SubscriptionProperties.Plan::getCurrency)
            .orElse("USD");
    return new AdminSubscriptionStats()
        .totalSubscriptions((long) subscriptions.size())
        .activeSubscriptions(active)
        .pastDueSubscriptions(pastDue)
        .inactiveSubscriptions(canceled)
        .monthlyRecurringAmount(monthly.stripTrailingZeros().toPlainString())
        .currency(currency);
  }

  private AdminSubscriptionItem toAdminItem(PaymentSubscriptionEntity subscription) {
    return new AdminSubscriptionItem()
        .subscriptionId(subscription.getSubscriptionId())
        .accountId(subscription.getAccountId())
        .accountBucket(accountBucket(subscription.getAccountId()))
        .provider(subscription.getProvider())
        .planKey(subscription.getPlanKey())
        .status(subscription.getStatus())
        .providerStatus(subscription.getProviderStatus())
        .providerSubscriptionId(subscription.getProviderSubscriptionId())
        .providerCustomerId(subscription.getProviderCustomerId())
        .currentPeriodEnd(offset(subscription.getCurrentPeriodEnd()))
        .gracePeriodEnd(offset(subscription.getGracePeriodEnd()))
        .managementUrl(subscription.getManagementUrl())
        .lastSyncedAt(offset(subscription.getLastSyncedAt()))
        .updatedAt(offset(subscription.getUpdatedAt()));
  }

  private AdminSubscriptionActionResponse actionResponse(
      String status, PaymentSubscriptionEntity subscription) {
    return new AdminSubscriptionActionResponse()
        .status(status)
        .accountId(subscription.getAccountId())
        .subscriptionId(subscription.getSubscriptionId())
        .subscription(toAdminItem(subscription));
  }

  private void ensureSubscriptionsEnabled() {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Subscriptions disabled");
    }
  }

  private SubscriptionProvider requireProvider(String providerKey) {
    try {
      return providerRegistry.require(providerKey);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private SubscriptionProperties.Plan requirePlan(String planKey) {
    try {
      return properties.requirePlan(planKey);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private SubscriptionProperties.ProviderPlan requireProviderPlan(
      SubscriptionProperties.Plan plan, String providerKey) {
    try {
      return properties.requireProviderPlan(plan, providerKey);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
  }

  private List<SubscriptionPlan> activePlans() {
    Optional<String> providerKey = configuredDefaultProviderKey();
    if (providerKey.isEmpty()) {
      return List.of();
    }
    return properties.getPlans().stream()
        .filter(SubscriptionProperties.Plan::isActive)
        .filter(plan -> hasConfiguredProviderPlan(plan, providerKey.get()))
        .map(plan -> toPlan(plan, providerKey.get()))
        .toList();
  }

  private Optional<String> configuredDefaultProviderKey() {
    try {
      SubscriptionProvider provider = providerRegistry.require(properties.getDefaultProvider());
      String providerKey = provider.providerKey();
      return properties.providerSettings(providerKey).isEnabled()
          ? Optional.of(providerKey)
          : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private boolean hasConfiguredProviderPlan(SubscriptionProperties.Plan plan, String providerKey) {
    try {
      properties.requireProviderPlan(plan, providerKey);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  private PaymentSubscriptionEntity requireSubscription(AdminSubscriptionActionRequest request) {
    if (request == null || StringUtils.isBlank(request.getSubscriptionId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing subscription id");
    }
    return subscriptionRepository
        .findById(request.getSubscriptionId())
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
  }

  private SubscriptionProvider.SubscriptionLookup lookup(PaymentSubscriptionEntity subscription) {
    SubscriptionProvider provider = requireProvider(subscription.getProvider());
    SubscriptionProperties.Plan plan = requirePlan(subscription.getPlanKey());
    SubscriptionProperties.ProviderPlan providerPlan =
        requireProviderPlan(plan, provider.providerKey());
    return new SubscriptionProvider.SubscriptionLookup(
        subscription.getAccountId(),
        plan,
        providerPlan,
        provider.customerSelector(
            subscription.getAccountId(), subscription.getProviderCustomerSelector()),
        subscription.getProviderSubscriptionId());
  }

  private boolean hasPremiumAccess(PaymentSubscriptionEntity subscription) {
    Instant accessUntil = accessUntil(subscription);
    return SubscriptionStatuses.PREMIUM_ACCESS_STATUSES.contains(subscription.getStatus())
        && accessUntil != null
        && accessUntil.isAfter(Instant.now());
  }

  private BigDecimal monthlyAmount(PaymentSubscriptionEntity subscription) {
    return properties.getPlans().stream()
        .filter(plan -> subscription.getPlanKey().equals(plan.getKey()))
        .findFirst()
        .map(plan -> monthlyEquivalent(plan.getPriceAmount(), plan.getBillingInterval()))
        .orElse(BigDecimal.ZERO);
  }

  private BigDecimal monthlyEquivalent(BigDecimal amount, String interval) {
    if (amount == null) {
      return BigDecimal.ZERO;
    }
    return switch (StringUtils.lowerCase(StringUtils.trimToEmpty(interval))) {
      case "yearly", "annual" -> amount.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
      case "weekly" ->
          amount
              .multiply(BigDecimal.valueOf(52))
              .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
      case "daily" ->
          amount
              .multiply(BigDecimal.valueOf(365))
              .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
      default -> amount;
    };
  }

  private String accountBucket(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(accountId.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 12);
    } catch (Exception e) {
      return accountId.substring(0, Math.min(12, accountId.length()));
    }
  }

  private Instant firstInstant(Instant... values) {
    for (Instant value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private OffsetDateTime offset(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  private record ProcessedWebhook(
      boolean processed,
      String accountId,
      String checkoutSessionId,
      String subscriptionId,
      String message) {}
}
