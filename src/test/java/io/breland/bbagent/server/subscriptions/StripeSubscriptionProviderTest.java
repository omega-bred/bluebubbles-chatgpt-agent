package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class StripeSubscriptionProviderTest {
  private static final String WEBHOOK_SECRET = "whsec_test";

  @Test
  void verifyAndParseWebhookAcceptsCheckoutSessionCompletedEvent() {
    StripeSubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "id": "evt_test_checkout",
          "type": "checkout.session.completed",
          "data": {
            "object": {
              "id": "cs_test_checkout",
              "object": "checkout.session",
              "customer": "cus_test_customer",
              "subscription": "sub_test_subscription",
              "metadata": {
                "bbagent_account_id": "account-1",
                "bbagent_checkout_id": "checkout-1",
                "bbagent_plan_key": "premium_monthly",
                "bbagent_provider": "stripe"
              }
            }
          }
        }
        """
            .getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = signedHeaders(payload);

    SubscriptionProvider.ProviderWebhookEvent event =
        provider.verifyAndParseWebhook(headers, payload);

    assertThat(event.providerEventId()).isEqualTo("evt_test_checkout");
    assertThat(event.eventType()).isEqualTo("checkout.session.completed");
    assertThat(event.accountId()).isEqualTo("account-1");
    assertThat(event.checkoutSessionId()).isEqualTo("checkout-1");
    assertThat(event.providerCheckoutId()).isEqualTo("cs_test_checkout");
    assertThat(event.providerSubscriptionId()).isEqualTo("sub_test_subscription");
    assertThat(event.customerSelector()).isEqualTo("cus_test_customer");
  }

  @Test
  void verifyAndParseWebhookAcceptsSubscriptionUpdatedEvent() {
    StripeSubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "id": "evt_test_subscription",
          "type": "customer.subscription.updated",
          "data": {
            "object": {
              "id": "sub_test_subscription",
              "object": "subscription",
              "customer": "cus_test_customer",
              "metadata": {
                "bbagent_account_id": "account-2",
                "bbagent_checkout_id": "checkout-2"
              }
            }
          }
        }
        """
            .getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = signedHeaders(payload);

    SubscriptionProvider.ProviderWebhookEvent event =
        provider.verifyAndParseWebhook(headers, payload);

    assertThat(event.eventType()).isEqualTo("customer.subscription.updated");
    assertThat(event.accountId()).isEqualTo("account-2");
    assertThat(event.checkoutSessionId()).isEqualTo("checkout-2");
    assertThat(event.providerSubscriptionId()).isEqualTo("sub_test_subscription");
    assertThat(event.customerSelector()).isEqualTo("cus_test_customer");
  }

  @Test
  void verifyAndParseWebhookRejectsInvalidSignature() {
    StripeSubscriptionProvider provider = provider();
    HttpHeaders headers = new HttpHeaders();
    headers.add("Stripe-Signature", "t=123,v1=bad");

    assertThatThrownBy(
            () ->
                provider.verifyAndParseWebhook(
                    headers, "{\"id\":\"evt_bad\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookVerificationException.class)
        .hasMessageContaining("Invalid Stripe webhook signature");
  }

  private StripeSubscriptionProvider provider() {
    SubscriptionProperties properties = new SubscriptionProperties();
    SubscriptionProperties.ProviderSettings settings =
        new SubscriptionProperties.ProviderSettings();
    settings.setApiKey("rk_test_fake");
    settings.setWebhookSecret(WEBHOOK_SECRET);
    settings.setTestModeOnly(true);
    properties.getProviders().put(StripeSubscriptionProvider.PROVIDER_KEY, settings);
    return new StripeSubscriptionProvider(new ObjectMapper(), properties);
  }

  private static HttpHeaders signedHeaders(byte[] payload) {
    long timestamp = Instant.now().getEpochSecond();
    String signedPayload = timestamp + "." + new String(payload, StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.add("Stripe-Signature", "t=" + timestamp + ",v1=" + hmac(signedPayload));
    return headers;
  }

  private static String hmac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
