package io.breland.bbagent.server.subscriptions;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;

public interface SubscriptionProvider {
  String providerKey();

  ProviderCheckoutSession createCheckout(CheckoutRequest request);

  ProviderPortalSession createPortalSession(PortalRequest request);

  ProviderSubscription fetchSubscription(SubscriptionLookup lookup);

  ProviderSubscription suspendSubscription(SubscriptionLookup lookup, String reason);

  ProviderSubscription unsuspendSubscription(SubscriptionLookup lookup);

  ProviderWebhookEvent verifyAndParseWebhook(HttpHeaders headers, byte[] body);

  default String customerSelector(String accountId, @Nullable String existing) {
    return existing == null || existing.isBlank() ? accountId : existing;
  }

  record CheckoutRequest(
      String accountId,
      @Nullable String email,
      String internalCheckoutSessionId,
      SubscriptionProperties.Plan plan,
      SubscriptionProperties.ProviderPlan providerPlan,
      String returnUrl,
      int durationMinutes) {}

  record PortalRequest(
      String accountId,
      @Nullable String email,
      SubscriptionProperties.Plan plan,
      SubscriptionProperties.ProviderPlan providerPlan,
      String customerSelector,
      String returnUrl) {}

  record SubscriptionLookup(
      String accountId,
      SubscriptionProperties.Plan plan,
      SubscriptionProperties.ProviderPlan providerPlan,
      String customerSelector,
      @Nullable String providerSubscriptionId) {}

  record ProviderCheckoutSession(
      String providerCheckoutId,
      String checkoutUrl,
      @Nullable String providerCustomerSelector,
      @Nullable Instant expiresAt,
      String rawPayload) {}

  record ProviderPortalSession(String providerPortalId, String portalUrl, String rawPayload) {}

  record ProviderSubscription(
      String providerSubscriptionId,
      @Nullable String providerCustomerId,
      String customerSelector,
      String providerStatus,
      String normalizedStatus,
      @Nullable Instant currentPeriodStart,
      @Nullable Instant currentPeriodEnd,
      @Nullable Instant trialEnd,
      @Nullable Instant gracePeriodEnd,
      @Nullable Instant canceledAt,
      boolean cancelAtPeriodEnd,
      @Nullable String managementUrl,
      String rawPayload) {}

  record ProviderWebhookEvent(
      String providerEventId,
      String eventType,
      @Nullable String accountId,
      @Nullable String checkoutSessionId,
      @Nullable String providerCheckoutId,
      @Nullable String providerSubscriptionId,
      @Nullable String customerSelector,
      String rawPayload) {}
}
