package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.codec.digest.HmacUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class BtcpaySubscriptionProviderTest {
  private static final String WEBHOOK_SECRET = "test-secret";
  private static final String SUBSCRIBER_URL =
      "https://btcpay.example/api/v1/stores/store/offerings/offering-1/subscribers/BBAGENT_ACCOUNT_ID%3Aaccount-1";

  @Test
  void createCheckoutReadsJsonResponse() {
    BtcpaySubscriptionProvider provider = provider();
    MockRestServiceServer server = mockServer(provider);
    var plan = new SubscriptionProperties.Plan();
    plan.setKey("premium_monthly");
    var request =
        new SubscriptionProvider.CheckoutRequest(
            "account-1",
            "person@example.com",
            "checkout-1",
            plan,
            lookup().providerPlan(),
            "https://bbagent.example/account",
            30,
            0);
    String response = "{\"id\":\"checkout-1\",\"url\":\"https://btcpay.example/pay/checkout-1\"}";
    server
        .expect(requestTo("https://btcpay.example/api/v1/plan-checkout"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "token token"))
        .andExpect(
            content()
                .json(
                    new ObjectMapper()
                        .valueToTree(BtcpaySubscriptionProvider.checkoutBody("store", request))
                        .toString()))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

    var result = provider.createCheckout(request);

    assertThat(result.providerCheckoutId()).isEqualTo("checkout-1");
    assertThat(result.checkoutUrl()).isEqualTo("https://btcpay.example/pay/checkout-1");
    assertThat(result.rawPayload()).isEqualTo(response);
    server.verify();
  }

  @Test
  void createPortalSessionReadsJsonResponse() {
    BtcpaySubscriptionProvider provider = provider();
    MockRestServiceServer server = mockServer(provider);
    String response = "{\"id\":\"portal-1\",\"url\":\"https://btcpay.example/portal/portal-1\"}";
    server
        .expect(requestTo("https://btcpay.example/api/v1/subscriber-portal"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "token token"))
        .andExpect(
            content()
                .json(
                    """
            {"storeId":"store","offeringId":"offering-1",
             "customerSelector":"BBAGENT_ACCOUNT_ID:account-1",
             "returnUrl":"https://bbagent.example/account"}
            """))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

    var result =
        provider.createPortalSession(
            new SubscriptionProvider.PortalRequest(
                "account-1",
                null,
                null,
                lookup().providerPlan(),
                "BBAGENT_ACCOUNT_ID:account-1",
                "https://bbagent.example/account"));

    assertThat(result.providerPortalId()).isEqualTo("portal-1");
    assertThat(result.portalUrl()).isEqualTo("https://btcpay.example/portal/portal-1");
    assertThat(result.rawPayload()).isEqualTo(response);
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({
    "GET, '', false, reason",
    "GET, '', true, reason",
    "POST, /suspend, false, reason",
    "POST, /suspend, true, reason",
    "POST, /suspend, false, ''",
    "POST, /suspend, false,",
    "POST, /unsuspend, false, reason",
    "POST, /unsuspend, true, reason"
  })
  void subscriberOperationsPreserveRequestsAndResponseMapping(
      HttpMethod httpMethod, String action, boolean emptyResponse, String reason) {
    BtcpaySubscriptionProvider provider = provider();
    MockRestServiceServer server = mockServer(provider);
    var expected =
        server
            .expect(requestTo(SUBSCRIBER_URL + action))
            .andExpect(method(httpMethod))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "token token"));
    if (action.equals("/suspend")) {
      String expectedReason =
          reason == null || reason.isBlank() ? "Suspended by bbagent admin" : reason;
      expected.andExpect(content().json("{\"reason\":\"" + expectedReason + "\"}"));
    } else {
      expected.andExpect(content().string(""));
    }
    String response =
        "{\"id\":\"subscriber-1\",\"customer\":{\"id\":\"customer-1\"},\"isActive\":true}";
    expected.andRespond(
        emptyResponse ? withNoContent() : withSuccess(response, MediaType.APPLICATION_JSON));

    var result = execute(provider, lookup(), action, reason);

    assertThat(result.providerSubscriptionId())
        .isEqualTo(emptyResponse ? "existing-subscription" : "subscriber-1");
    assertThat(result.providerCustomerId()).isEqualTo(emptyResponse ? null : "customer-1");
    assertThat(result.customerSelector()).isEqualTo("BBAGENT_ACCOUNT_ID:account-1");
    assertThat(result.normalizedStatus()).isEqualTo(emptyResponse ? "expired" : "active");
    assertThat(result.rawPayload()).isEqualTo(emptyResponse ? "{}" : response);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/suspend", "/unsuspend"})
  void subscriberOperationsRejectMissingPlanBeforeSending(String action) {
    BtcpaySubscriptionProvider provider = provider();
    MockRestServiceServer server = mockServer(provider);
    var lookup = lookup();
    lookup.providerPlan().setOfferingId(null);

    assertThatThrownBy(() -> execute(provider, lookup, action, "reason"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BTCPay subscription plan is not configured");
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/suspend", "/unsuspend"})
  void subscriberOperationsPropagateProviderErrors(String action) {
    BtcpaySubscriptionProvider provider = provider();
    MockRestServiceServer server = mockServer(provider);
    server.expect(requestTo(SUBSCRIBER_URL + action)).andRespond(withServerError());

    assertThatThrownBy(() -> execute(provider, lookup(), action, "reason"))
        .isInstanceOf(HttpServerErrorException.class);
    server.verify();
  }

  private static MockRestServiceServer mockServer(BtcpaySubscriptionProvider provider) {
    var builder = ((RestClient) ReflectionTestUtils.getField(provider, "restClient")).mutate();
    var server = MockRestServiceServer.bindTo(builder).build();
    ReflectionTestUtils.setField(provider, "restClient", builder.build());
    return server;
  }

  private static SubscriptionProvider.SubscriptionLookup lookup() {
    var providerPlan = new SubscriptionProperties.ProviderPlan();
    providerPlan.setOfferingId("offering-1");
    providerPlan.setPlanId("plan-1");
    return new SubscriptionProvider.SubscriptionLookup(
        "account-1", null, providerPlan, "BBAGENT_ACCOUNT_ID:account-1", "existing-subscription");
  }

  private static SubscriptionProvider.ProviderSubscription execute(
      BtcpaySubscriptionProvider provider,
      SubscriptionProvider.SubscriptionLookup lookup,
      String action,
      String reason) {
    return switch (action) {
      case "" -> provider.fetchSubscription(lookup);
      case "/suspend" -> provider.suspendSubscription(lookup, reason);
      case "/unsuspend" -> provider.unsuspendSubscription(lookup);
      default -> throw new IllegalArgumentException(action);
    };
  }

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
