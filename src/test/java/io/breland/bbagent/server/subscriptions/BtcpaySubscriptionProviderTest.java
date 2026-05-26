package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.codec.digest.HmacUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class BtcpaySubscriptionProviderTest {
  private static final String WEBHOOK_SECRET = "test-secret";

  @Test
  void verifyAndParseWebhookAcceptsSignedPayloadAndExtractsMetadata() {
    BtcpaySubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "deliveryId": "delivery-1",
          "type": "SubscriberUpdated",
          "invoice": {
            "metadata": {
              "bbagent_account_id": "account-1",
              "bbagent_checkout_id": "checkout-1"
            }
          },
          "subscriberId": "subscriber-1",
          "customerSelector": "BBAGENT_ACCOUNT_ID:account-1"
        }
        """
            .getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.add("BTCPAY-SIG", " sha256=" + hmac(payload) + " ");

    SubscriptionProvider.ProviderWebhookEvent event =
        provider.verifyAndParseWebhook(headers, payload);

    assertThat(event.providerEventId()).isEqualTo("delivery-1");
    assertThat(event.eventType()).isEqualTo("SubscriberUpdated");
    assertThat(event.accountId()).isEqualTo("account-1");
    assertThat(event.checkoutSessionId()).isEqualTo("checkout-1");
    assertThat(event.providerSubscriptionId()).isEqualTo("subscriber-1");
    assertThat(event.customerSelector()).isEqualTo("BBAGENT_ACCOUNT_ID:account-1");
  }

  @Test
  void verifyAndParseWebhookRejectsInvalidSignature() {
    BtcpaySubscriptionProvider provider = provider();
    HttpHeaders headers = new HttpHeaders();
    headers.add("BTCPAY-SIG", "sha256=bad");

    assertThatThrownBy(
            () ->
                provider.verifyAndParseWebhook(
                    headers, "{\"deliveryId\":\"delivery-1\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookVerificationException.class)
        .hasMessageContaining("Invalid BTCPay webhook signature");
  }

  @Test
  @SuppressWarnings("unchecked")
  void checkoutBodyCreatesNewSubscriberCheckoutWithoutCustomerSelector() {
    SubscriptionProperties.Plan plan = new SubscriptionProperties.Plan();
    plan.setKey("premium_monthly");
    SubscriptionProperties.ProviderPlan providerPlan = new SubscriptionProperties.ProviderPlan();
    providerPlan.setOfferingId("offering-1");
    providerPlan.setPlanId("plan-1");
    SubscriptionProvider.CheckoutRequest request =
        new SubscriptionProvider.CheckoutRequest(
            "account-1",
            "person@example.com",
            "checkout-1",
            plan,
            providerPlan,
            "https://bbagent.example/account",
            30,
            30);

    Map<String, Object> body = BtcpaySubscriptionProvider.checkoutBody("store-1", request);

    assertThat(body)
        .containsEntry("storeId", "store-1")
        .containsEntry("offeringId", "offering-1")
        .containsEntry("planId", "plan-1")
        .containsEntry("isTrial", true)
        .containsEntry("newSubscriberEmail", "person@example.com")
        .doesNotContainKey("customerSelector");
    assertThat((Map<String, Object>) body.get("newSubscriberMetadata"))
        .containsEntry("bbagent_account_id", "account-1")
        .containsEntry("bbagent_checkout_id", "checkout-1");
  }

  @Test
  void verifyAndParseWebhookDerivesCustomerSelectorFromSubscriberIdentity() {
    BtcpaySubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "deliveryId": "delivery-2",
          "type": "SubscriberCreated",
          "subscriber": {
            "metadata": {
              "bbagent_account_id": "account-2",
              "bbagent_checkout_id": "checkout-2"
            },
            "customer": {
              "id": "cust_customer-2",
              "identities": {
                "Email": "person@example.com"
              }
            }
          }
        }
        """
            .getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.add("BTCPAY-SIG", "sha256=" + hmac(payload));

    SubscriptionProvider.ProviderWebhookEvent event =
        provider.verifyAndParseWebhook(headers, payload);

    assertThat(event.accountId()).isEqualTo("account-2");
    assertThat(event.checkoutSessionId()).isEqualTo("checkout-2");
    assertThat(event.customerSelector()).isEqualTo("Email:person@example.com");
  }

  private BtcpaySubscriptionProvider provider() {
    SubscriptionProperties properties = new SubscriptionProperties();
    SubscriptionProperties.ProviderSettings settings =
        new SubscriptionProperties.ProviderSettings();
    settings.setBaseUrl("https://btcpay.example");
    settings.setApiKey("token");
    settings.setStoreId("store");
    settings.setWebhookSecret(WEBHOOK_SECRET);
    properties.getProviders().put(BtcpaySubscriptionProvider.PROVIDER_KEY, settings);
    return new BtcpaySubscriptionProvider(new ObjectMapper(), properties);
  }

  private static String hmac(byte[] payload) {
    return HmacUtils.hmacSha256Hex(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), payload);
  }
}
