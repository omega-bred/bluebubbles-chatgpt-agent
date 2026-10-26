package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.codec.digest.HmacUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

class StripeSubscriptionProviderTest {
  private static final String WEBHOOK_SECRET = "whsec_test";

  @ParameterizedTest
  @ValueSource(strings = {"2026-04-22.dahlia", "2026-09-30.endive"})
  void verifyAndParseWebhookAcceptsCheckoutSessionCompletedEvent(String apiVersion) {
    StripeSubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "id": "evt_test_checkout",
          "api_version": "%s",
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
            .formatted(apiVersion)
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

  @ParameterizedTest
  @ValueSource(strings = {"2026-04-22.dahlia", "2026-09-30.endive"})
  void verifyAndParseWebhookAcceptsSubscriptionUpdatedEvent(String apiVersion) {
    StripeSubscriptionProvider provider = provider();
    byte[] payload =
        """
        {
          "id": "evt_test_subscription",
          "api_version": "%s",
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
            .formatted(apiVersion)
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

  @Test
  void checkoutUsesMatchingSdkApiVersionAndPreservesReconciliationMetadata() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var body = new AtomicReference<Map<String, String>>();
    var version = new AtomicReference<String>();
    var idempotencyKey = new AtomicReference<String>();
    server.createContext(
        "/v1/checkout/sessions",
        exchange -> {
          version.set(exchange.getRequestHeaders().getFirst("Stripe-Version"));
          idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          body.set(
              form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] response =
              """
          {"id":"cs_test_checkout","object":"checkout.session","customer":"cus_test_customer",
           "url":"https://checkout.stripe.test/session","expires_at":1800000000}
          """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    StripeClient client =
        StripeClient.builder()
            .setApiKey("rk_test_fake")
            .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
    try (var clients =
        mockConstruction(StripeClient.class, withSettings().defaultAnswer(delegatesTo(client)))) {
      var plan = new SubscriptionProperties.Plan();
      plan.setKey("premium_monthly");
      var providerPlan = new SubscriptionProperties.ProviderPlan();
      providerPlan.setPlanId("price_test");
      var result =
          provider()
              .createCheckout(
                  new SubscriptionProvider.CheckoutRequest(
                      "account-1",
                      "person@example.com",
                      "checkout-1",
                      plan,
                      providerPlan,
                      "https://bluechat.example/account",
                      30,
                      7));

      assertThat(clients.constructed()).hasSize(1);
      assertThat(version.get()).isEqualTo("2026-09-30.endive");
      assertThat(idempotencyKey.get()).isEqualTo("bbagent-checkout-checkout-1");
      assertThat(body.get())
          .containsEntry("mode", "subscription")
          .containsEntry("line_items[0][price]", "price_test")
          .containsEntry("line_items[0][quantity]", "1")
          .containsEntry("client_reference_id", "account-1")
          .containsEntry("customer_email", "person@example.com")
          .containsEntry("subscription_data[trial_period_days]", "7");
      Map.of(
              "bbagent_account_id",
              "account-1",
              "bbagent_checkout_id",
              "checkout-1",
              "bbagent_plan_key",
              "premium_monthly",
              "bbagent_provider",
              "stripe")
          .forEach(
              (key, value) ->
                  assertThat(body.get())
                      .containsEntry("metadata[" + key + "]", value)
                      .containsEntry("subscription_data[metadata][" + key + "]", value));
      assertThat(result.providerCheckoutId()).isEqualTo("cs_test_checkout");
      assertThat(result.providerCustomerSelector()).isEqualTo("cus_test_customer");
      assertThat(result.checkoutUrl()).isEqualTo("https://checkout.stripe.test/session");
      assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochSecond(1800000000));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void subscriptionCancellationPreservesItemPeriodsAndCancellationState() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var body = new AtomicReference<Map<String, String>>();
    server.createContext(
        "/v1/subscriptions/sub_test",
        exchange -> {
          body.set(
              form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] response =
              """
          {"id":"sub_test","object":"subscription","customer":"cus_test","status":"active",
           "cancel_at_period_end":true,"items":{"object":"list","data":[
             {"id":"si_1","object":"subscription_item","current_period_start":1800000000,
              "current_period_end":1802592000},
             {"id":"si_2","object":"subscription_item","current_period_start":1800086400,
              "current_period_end":1802678400}]}}
          """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    StripeClient client =
        StripeClient.builder()
            .setApiKey("rk_test_fake")
            .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
    try (var clients =
        mockConstruction(StripeClient.class, withSettings().defaultAnswer(delegatesTo(client)))) {
      var lookup =
          new SubscriptionProvider.SubscriptionLookup(
              "account-1", null, null, "cus_test", "sub_test");
      var result = provider().suspendSubscription(lookup, "requested");
      assertThat(clients.constructed()).hasSize(1);
      assertThat(body.get()).containsEntry("cancel_at_period_end", "true");
      assertThat(result.providerSubscriptionId()).isEqualTo("sub_test");
      assertThat(result.normalizedStatus()).isEqualTo(SubscriptionStatuses.SUBSCRIPTION_ACTIVE);
      assertThat(result.cancelAtPeriodEnd()).isTrue();
      assertThat(result.currentPeriodStart()).isEqualTo(Instant.ofEpochSecond(1800000000));
      assertThat(result.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1802678400));
    } finally {
      server.stop(0);
    }
  }

  private static Map<String, String> form(String body) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String pair : body.split("&")) {
      String[] parts = pair.split("=", 2);
      values.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return values;
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
    return HmacUtils.hmacSha256Hex(
        WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8));
  }
}
