package io.breland.bbagent.server.subscriptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.SubscriptionStoreKitTransactionRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class AppleStoreKitSubscriptionProvider implements SubscriptionProvider {
  static final String PROVIDER_KEY = "apple";

  private final AppleStoreKitVerification verification;
  private final ObjectMapper objectMapper;

  public AppleStoreKitSubscriptionProvider(
      AppleStoreKitVerification verification, ObjectMapper objectMapper) {
    this.verification = verification;
    this.objectMapper = objectMapper;
  }

  @Override
  public String providerKey() {
    return PROVIDER_KEY;
  }

  @Override
  public ProviderCheckoutSession createCheckout(CheckoutRequest request) {
    throw new IllegalStateException("Apple subscriptions must be purchased through StoreKit");
  }

  @Override
  public ProviderPortalSession createPortalSession(PortalRequest request) {
    return new ProviderPortalSession(
        "apple-subscriptions",
        "https://apps.apple.com/account/subscriptions",
        "{\"provider\":\"apple\"}");
  }

  @Override
  public ProviderSubscription fetchSubscription(SubscriptionLookup lookup) {
    throw new IllegalStateException(
        "Apple subscription sync requires a fresh StoreKit transaction or server notification");
  }

  @Override
  public ProviderSubscription suspendSubscription(SubscriptionLookup lookup, String reason) {
    throw new IllegalStateException("Apple subscriptions cannot be suspended from BlueChat");
  }

  @Override
  public ProviderSubscription unsuspendSubscription(SubscriptionLookup lookup) {
    throw new IllegalStateException("Apple subscriptions cannot be unsuspended from BlueChat");
  }

  @Override
  public ProviderWebhookEvent verifyAndParseWebhook(HttpHeaders headers, byte[] body) {
    String payload = body == null ? "" : new String(body, StandardCharsets.UTF_8);
    String signedPayload = signedPayload(payload);
    AppleStoreKitVerification.VerifiedNotification notification =
        verification.verifyNotification(signedPayload);
    AppleStoreKitVerification.VerifiedTransaction transaction = notification.transaction();
    return new ProviderWebhookEvent(
        firstNonBlank(notification.notificationUuid(), UUID.randomUUID().toString()),
        firstNonBlank(notification.notificationType(), "apple_notification"),
        null,
        null,
        null,
        transaction == null ? null : originalTransactionId(transaction),
        transaction == null || transaction.appAccountToken() == null
            ? null
            : transaction.appAccountToken().toString(),
        notification.rawPayload());
  }

  public ProviderSubscription validateTransaction(
      String accountId,
      UUID expectedAppAccountToken,
      SubscriptionProperties.Plan plan,
      SubscriptionProperties.ProviderPlan providerPlan,
      SubscriptionStoreKitTransactionRequest request) {
    AppleStoreKitVerification.VerifiedTransaction transaction =
        verification.verifyTransaction(
            request.getSignedTransactionInfo(), request.getSignedRenewalInfo());
    String requestedProductId = StringUtils.trimToNull(request.getProductId());
    String configuredProductId = StringUtils.trimToNull(providerPlan.getPlanId());
    String transactionProductId = StringUtils.trimToNull(transaction.productId());
    if (configuredProductId != null && !configuredProductId.equals(transactionProductId)) {
      throw new IllegalArgumentException("StoreKit product does not match configured plan");
    }
    if (requestedProductId != null && !requestedProductId.equals(transactionProductId)) {
      throw new IllegalArgumentException("StoreKit product does not match request");
    }
    if (transaction.appAccountToken() == null
        || !expectedAppAccountToken.equals(transaction.appAccountToken())) {
      throw new IllegalArgumentException("StoreKit appAccountToken does not match this account");
    }
    return toProviderSubscription(accountId, transaction);
  }

  private ProviderSubscription toProviderSubscription(
      String accountId, AppleStoreKitVerification.VerifiedTransaction transaction) {
    Instant now = Instant.now();
    Instant expiresAt = transaction.expiresDate();
    Instant revokedAt = transaction.revocationDate();
    String normalizedStatus;
    if (revokedAt != null) {
      normalizedStatus = SubscriptionStatuses.SUBSCRIPTION_CANCELED;
    } else if (expiresAt == null || expiresAt.isAfter(now)) {
      normalizedStatus = SubscriptionStatuses.SUBSCRIPTION_ACTIVE;
    } else {
      normalizedStatus = SubscriptionStatuses.SUBSCRIPTION_EXPIRED;
    }
    return new ProviderSubscription(
        originalTransactionId(transaction),
        transaction.appAccountToken() == null ? null : transaction.appAccountToken().toString(),
        transaction.appAccountToken() == null
            ? accountId
            : transaction.appAccountToken().toString(),
        transaction.type(),
        normalizedStatus,
        transaction.purchaseDate(),
        expiresAt,
        null,
        null,
        revokedAt,
        false,
        "https://apps.apple.com/account/subscriptions",
        transaction.rawPayload());
  }

  private String signedPayload(String payload) {
    try {
      Map<?, ?> body = objectMapper.readValue(payload, Map.class);
      Object signedPayload = body.get("signedPayload");
      return signedPayload == null ? null : StringUtils.trimToNull(String.valueOf(signedPayload));
    } catch (Exception e) {
      return StringUtils.trimToNull(payload);
    }
  }

  private String originalTransactionId(AppleStoreKitVerification.VerifiedTransaction transaction) {
    return firstNonBlank(transaction.originalTransactionId(), transaction.transactionId());
  }

  private String firstNonBlank(@Nullable String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }
}
