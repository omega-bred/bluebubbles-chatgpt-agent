package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.model.SubscriptionStoreKitTransactionRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AppleStoreKitSubscriptionProviderTest {
  @Test
  void validateTransactionMapsVerifiedStoreKitTransactionToSubscription() {
    UUID appAccountToken = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(3600);
    AppleStoreKitSubscriptionProvider provider =
        new AppleStoreKitSubscriptionProvider(
            new FakeVerification(appAccountToken, expiresAt), new ObjectMapper());

    SubscriptionProvider.ProviderSubscription subscription =
        provider.validateTransaction(
            "account-1",
            appAccountToken,
            plan(),
            providerPlan(),
            new SubscriptionStoreKitTransactionRequest()
                .signedTransactionInfo("signed-transaction")
                .productId("land.bre.bluechat.premium.monthly"));

    assertThat(subscription.providerSubscriptionId()).isEqualTo("original-transaction-1");
    assertThat(subscription.customerSelector()).isEqualTo(appAccountToken.toString());
    assertThat(subscription.normalizedStatus()).isEqualTo(SubscriptionStatuses.SUBSCRIPTION_ACTIVE);
    assertThat(subscription.currentPeriodEnd()).isEqualTo(expiresAt);
  }

  @Test
  void validateTransactionRejectsMismatchedAppAccountToken() {
    AppleStoreKitSubscriptionProvider provider =
        new AppleStoreKitSubscriptionProvider(
            new FakeVerification(UUID.randomUUID(), Instant.now().plusSeconds(3600)),
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                provider.validateTransaction(
                    "account-1",
                    UUID.randomUUID(),
                    plan(),
                    providerPlan(),
                    new SubscriptionStoreKitTransactionRequest()
                        .signedTransactionInfo("signed-transaction")
                        .productId("land.bre.bluechat.premium.monthly")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("appAccountToken");
  }

  @Test
  void verifyAndParseWebhookReadsSignedPayloadEnvelope() {
    FakeVerification verification =
        new FakeVerification(UUID.randomUUID(), Instant.now().plusSeconds(3600));
    AppleStoreKitSubscriptionProvider provider =
        new AppleStoreKitSubscriptionProvider(verification, new ObjectMapper());

    provider.verifyAndParseWebhook(
        new HttpHeaders(),
        "{\"signedPayload\":\"signed-notification\"}".getBytes(StandardCharsets.UTF_8));

    assertThat(verification.notificationPayload).isEqualTo("signed-notification");
  }

  private SubscriptionProperties.Plan plan() {
    SubscriptionProperties.Plan plan = new SubscriptionProperties.Plan();
    plan.setKey("premium_monthly");
    return plan;
  }

  private SubscriptionProperties.ProviderPlan providerPlan() {
    SubscriptionProperties.ProviderPlan providerPlan = new SubscriptionProperties.ProviderPlan();
    providerPlan.setOfferingId("premium");
    providerPlan.setPlanId("land.bre.bluechat.premium.monthly");
    return providerPlan;
  }

  private static class FakeVerification implements AppleStoreKitVerification {
    private final UUID appAccountToken;
    private final Instant expiresAt;
    private String notificationPayload;

    FakeVerification(UUID appAccountToken, Instant expiresAt) {
      this.appAccountToken = appAccountToken;
      this.expiresAt = expiresAt;
    }

    @Override
    public VerifiedTransaction verifyTransaction(
        String signedTransactionInfo, String signedRenewalInfo) {
      return transaction();
    }

    @Override
    public VerifiedNotification verifyNotification(String signedPayload) {
      notificationPayload = signedPayload;
      return new VerifiedNotification(
          "notification-1",
          "DID_RENEW",
          null,
          transaction(),
          "{\"signed_transaction_info\":\"signed\"}");
    }

    private VerifiedTransaction transaction() {
      return new VerifiedTransaction(
          "transaction-1",
          "original-transaction-1",
          "land.bre.bluechat.premium.monthly",
          appAccountToken,
          "Xcode",
          Instant.now().minusSeconds(60),
          expiresAt,
          null,
          "Auto-Renewable Subscription",
          "{}");
    }
  }
}
