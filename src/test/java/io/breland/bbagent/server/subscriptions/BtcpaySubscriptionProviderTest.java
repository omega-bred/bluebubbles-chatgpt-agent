package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
