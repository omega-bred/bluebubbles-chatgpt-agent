package io.breland.bbagent.server.subscriptions;

import static io.breland.bbagent.server.subscriptions.SubscriptionJson.findObject;
import static io.breland.bbagent.server.subscriptions.SubscriptionJson.firstNonBlank;
import static io.breland.bbagent.server.subscriptions.SubscriptionJson.firstText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BtcpaySubscriptionProvider implements SubscriptionProvider {
  public static final String PROVIDER_KEY = "btcpay";
  private static final String SIGNATURE_HEADER = "BTCPAY-SIG";
  private static final String SIGNATURE_PREFIX = "sha256=";
  private static final String CUSTOMER_SELECTOR_KEY = "BBAGENT_ACCOUNT_ID";

  private final ObjectMapper objectMapper;
  private final SubscriptionProperties properties;
  private final RestClient restClient;

  public BtcpaySubscriptionProvider(ObjectMapper objectMapper, SubscriptionProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.restClient = RestClient.builder().baseUrl(baseUrl()).build();
  }

  @Override
  public String providerKey() {
    return PROVIDER_KEY;
  }

  @Override
  public ProviderCheckoutSession createCheckout(CheckoutRequest request) {
    ensureConfigured();
    ensureProviderPlanConfigured(request.providerPlan());
    Map<String, Object> body = checkoutBody(settings().getStoreId(), request);

    JsonNode response = postJson("/api/v1/plan-checkout", body);
    String id = firstText(response, "id", "planCheckoutId", "checkoutId");
    String url = firstText(response, "url", "checkoutUrl", "checkoutLink", "link", "redirectUrl");
    if (StringUtils.isBlank(url) && StringUtils.isNotBlank(id)) {
      url = baseUrl() + "/plan-checkout/" + id;
    }
    return new ProviderCheckoutSession(
        firstNonBlank(id, request.internalCheckoutSessionId()),
        url,
        null,
        epoch(firstText(response, "expiration", "expires", "expiresAt")),
        json(response));
  }

  @Override
  public ProviderPortalSession createPortalSession(PortalRequest request) {
    ensureConfigured();
    ensureProviderPlanConfigured(request.providerPlan());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("storeId", settings().getStoreId());
    body.put("offeringId", request.providerPlan().getOfferingId());
    body.put("customerSelector", request.customerSelector());
    body.put("returnUrl", request.returnUrl());

    JsonNode response = postJson("/api/v1/subscriber-portal", body);
    String id = firstText(response, "id", "portalSessionId");
    String url = firstText(response, "url", "portalUrl", "portalLink", "link");
    if (StringUtils.isBlank(url) && StringUtils.isNotBlank(id)) {
      url = baseUrl() + "/subscriber-portal/" + id;
    }
    return new ProviderPortalSession(id, url, json(response));
  }

  @Override
  public ProviderSubscription fetchSubscription(SubscriptionLookup lookup) {
    ensureConfigured();
    ensureProviderPlanConfigured(lookup.providerPlan());
    JsonNode response =
        restClient
            .get()
            .uri(
                "/api/v1/stores/{storeId}/offerings/{offeringId}/subscribers/{customerSelector}",
                settings().getStoreId(),
                lookup.providerPlan().getOfferingId(),
                lookup.customerSelector())
            .header(HttpHeaders.AUTHORIZATION, authHeader())
            .retrieve()
            .body(JsonNode.class);
    return toProviderSubscription(
        lookup, response == null ? objectMapper.createObjectNode() : response);
  }

  @Override
  public ProviderSubscription suspendSubscription(SubscriptionLookup lookup, String reason) {
    ensureConfigured();
    ensureProviderPlanConfigured(lookup.providerPlan());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", StringUtils.defaultIfBlank(reason, "Suspended by bbagent admin"));
    JsonNode response =
        restClient
            .post()
            .uri(
                "/api/v1/stores/{storeId}/offerings/{offeringId}/subscribers/{customerSelector}/suspend",
                settings().getStoreId(),
                lookup.providerPlan().getOfferingId(),
                lookup.customerSelector())
            .header(HttpHeaders.AUTHORIZATION, authHeader())
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    return toProviderSubscription(
        lookup, response == null ? objectMapper.createObjectNode() : response);
  }

  @Override
  public ProviderSubscription unsuspendSubscription(SubscriptionLookup lookup) {
    ensureConfigured();
    ensureProviderPlanConfigured(lookup.providerPlan());
    JsonNode response =
        restClient
            .post()
            .uri(
                "/api/v1/stores/{storeId}/offerings/{offeringId}/subscribers/{customerSelector}/unsuspend",
                settings().getStoreId(),
                lookup.providerPlan().getOfferingId(),
                lookup.customerSelector())
            .header(HttpHeaders.AUTHORIZATION, authHeader())
            .retrieve()
            .body(JsonNode.class);
    return toProviderSubscription(
        lookup, response == null ? objectMapper.createObjectNode() : response);
  }

  @Override
  public ProviderWebhookEvent verifyAndParseWebhook(HttpHeaders headers, byte[] body) {
    ensureWebhookConfigured();
    String signature =
        firstNonBlank(headers.getFirst(SIGNATURE_HEADER), headers.getFirst("BTCPay-Sig"));
    if (!validSignature(signature, body)) {
      throw new WebhookVerificationException("Invalid BTCPay webhook signature");
    }
    JsonNode payload;
    try {
      payload = objectMapper.readTree(body == null ? new byte[0] : body);
    } catch (Exception e) {
      throw new WebhookVerificationException("Invalid BTCPay webhook payload", e);
    }
    String rawPayload = json(payload);
    String eventType = firstText(payload, "type", "eventType");
    String providerEventId =
        firstNonBlank(
            firstText(payload, "deliveryId", "webhookDeliveryId", "id"),
            eventType
                + ":"
                + firstText(payload, "invoiceId", "subscriptionId")
                + ":"
                + DigestUtils.sha256Hex(rawPayload));
    return new ProviderWebhookEvent(
        providerEventId,
        firstNonBlank(eventType, "unknown"),
        findText(payload, "bbagent_account_id"),
        findText(payload, "bbagent_checkout_id"),
        firstNonBlank(
            findText(payload, "planCheckoutId"),
            findText(payload, "checkoutPlanId"),
            findText(payload, "checkoutId")),
        firstNonBlank(findText(payload, "subscriberId"), findText(payload, "subscriptionId")),
        providerCustomerSelector(payload),
        rawPayload);
  }

  @Override
  public String customerSelector(String accountId, String existing) {
    return StringUtils.isNotBlank(existing) ? existing : customerSelector(accountId);
  }

  static String customerSelector(String accountId) {
    return CUSTOMER_SELECTOR_KEY + ":" + accountId;
  }

  static Map<String, Object> checkoutBody(String storeId, CheckoutRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("storeId", storeId);
    body.put("offeringId", request.providerPlan().getOfferingId());
    body.put("planId", request.providerPlan().getPlanId());
    body.put("durationMinutes", request.durationMinutes());
    body.put("isTrial", request.trialDurationDays() > 0);
    body.put("successRedirectLink", request.returnUrl());
    if (StringUtils.isNotBlank(request.email())) {
      body.put("newSubscriberEmail", request.email());
    }
    body.put("metadata", metadata(request));
    body.put("invoiceMetadata", metadata(request));
    body.put("newSubscriberMetadata", metadata(request));
    return body;
  }

  private ProviderSubscription toProviderSubscription(
      SubscriptionLookup lookup, JsonNode response) {
    JsonNode customer = response.path("customer");
    String providerCustomerId =
        firstNonBlank(firstText(customer, "id"), firstText(response, "customerId"));
    String providerSubscriptionId =
        firstNonBlank(
            firstText(response, "id", "subscriptionId", "subscriberId"),
            providerCustomerId,
            lookup.providerSubscriptionId(),
            lookup.customerSelector());
    Instant periodEnd = epoch(firstText(response, "periodEnd", "currentPeriodEnd"));
    Instant trialEnd = epoch(firstText(response, "trialEnd"));
    Instant graceEnd = epoch(firstText(response, "gracePeriodEnd"));
    boolean active = booleanValue(response, "isActive");
    boolean suspended = booleanValue(response, "isSuspended", "suspended");
    String normalizedStatus = normalizedStatus(active, suspended, periodEnd, trialEnd, graceEnd);
    return new ProviderSubscription(
        providerSubscriptionId,
        providerCustomerId,
        lookup.customerSelector(),
        firstNonBlank(firstText(response, "status", "state"), normalizedStatus),
        normalizedStatus,
        epoch(firstText(response, "periodStart", "currentPeriodStart", "created")),
        periodEnd,
        trialEnd,
        graceEnd,
        epoch(firstText(response, "canceledAt", "cancelledAt")),
        booleanValue(response, "cancelAtPeriodEnd"),
        firstText(response, "managementUrl", "portalUrl", "portalLink"),
        json(response));
  }

  private String normalizedStatus(
      boolean active, boolean suspended, Instant periodEnd, Instant trialEnd, Instant graceEnd) {
    Instant now = Instant.now();
    if (suspended) {
      return SubscriptionStatuses.SUBSCRIPTION_SUSPENDED;
    }
    if (active && trialEnd != null && trialEnd.isAfter(now)) {
      return SubscriptionStatuses.SUBSCRIPTION_TRIALING;
    }
    if (active) {
      return SubscriptionStatuses.SUBSCRIPTION_ACTIVE;
    }
    if (graceEnd != null && graceEnd.isAfter(now)) {
      return SubscriptionStatuses.SUBSCRIPTION_GRACE;
    }
    if (periodEnd != null && periodEnd.isAfter(now)) {
      return SubscriptionStatuses.SUBSCRIPTION_PAST_DUE;
    }
    return SubscriptionStatuses.SUBSCRIPTION_EXPIRED;
  }

  private static Map<String, Object> metadata(CheckoutRequest request) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("bbagent_account_id", request.accountId());
    metadata.put("bbagent_checkout_id", request.internalCheckoutSessionId());
    metadata.put("bbagent_plan_key", request.plan().getKey());
    metadata.put("bbagent_provider", PROVIDER_KEY);
    return metadata;
  }

  private String providerCustomerSelector(JsonNode payload) {
    String explicit =
        firstNonBlank(findText(payload, "customerSelector"), findText(payload, "CustomerSelector"));
    if (StringUtils.isNotBlank(explicit)) {
      return explicit;
    }
    JsonNode identities =
        firstNonNull(findObject(payload, "identities"), findObject(payload, "Identities"));
    if (identities != null && identities.isObject()) {
      String email = firstText(identities, "Email", "email");
      if (StringUtils.isNotBlank(email)) {
        return "Email:" + email;
      }
      for (var field : identities.properties()) {
        String value = field.getValue().asText(null);
        if (StringUtils.isNoneBlank(field.getKey(), value)) {
          return field.getKey() + ":" + value;
        }
      }
    }
    JsonNode customer =
        firstNonNull(findObject(payload, "customer"), findObject(payload, "Customer"));
    return firstText(customer, "id", "Id");
  }

  private String authHeader() {
    return "token " + StringUtils.trimToEmpty(settings().getApiKey());
  }

  private JsonNode postJson(String uri, Map<String, Object> body) {
    JsonNode response =
        restClient
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader())
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    return response == null ? objectMapper.createObjectNode() : response;
  }

  private boolean validSignature(String signature, byte[] payload) {
    String normalized = StringUtils.trimToEmpty(signature);
    if (!normalized.startsWith(SIGNATURE_PREFIX)) {
      return false;
    }
    byte[] expected = (SIGNATURE_PREFIX + hmacSha256(payload)).getBytes(StandardCharsets.UTF_8);
    byte[] actual = normalized.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }

  private String hmacSha256(byte[] payload) {
    return HmacUtils.hmacSha256Hex(
        settings().getWebhookSecret().getBytes(StandardCharsets.UTF_8),
        payload == null ? new byte[0] : payload);
  }

  private Instant epoch(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      if (value.matches("^-?\\d+(\\.\\d+)?$")) {
        long numeric =
            value.contains(".") ? (long) Double.parseDouble(value) : Long.parseLong(value);
        return Math.abs(numeric) < 10_000_000_000L
            ? Instant.ofEpochSecond(numeric)
            : Instant.ofEpochMilli(numeric);
      }
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }

  private boolean booleanValue(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value != null && !value.isNull()) {
        if (value.isBoolean()) {
          return value.asBoolean();
        }
        if (value.isTextual()) {
          return Boolean.parseBoolean(value.asText());
        }
      }
    }
    return false;
  }

  private String findText(JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode direct = node.get(fieldName);
    if (direct != null && !direct.isNull() && StringUtils.isNotBlank(direct.asText(null))) {
      return direct.asText();
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        String found = findText(child, fieldName);
        if (StringUtils.isNotBlank(found)) {
          return found;
        }
      }
    }
    return null;
  }

  private String json(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      return "{}";
    }
  }

  private void ensureConfigured() {
    if (!settings().isEnabled()
        || StringUtils.isAnyBlank(
            settings().getBaseUrl(), settings().getApiKey(), settings().getStoreId())) {
      throw new IllegalStateException("BTCPay subscription provider is not configured");
    }
  }

  private void ensureProviderPlanConfigured(SubscriptionProperties.ProviderPlan providerPlan) {
    if (providerPlan == null
        || StringUtils.isAnyBlank(providerPlan.getOfferingId(), providerPlan.getPlanId())) {
      throw new IllegalStateException("BTCPay subscription plan is not configured");
    }
  }

  private void ensureWebhookConfigured() {
    if (StringUtils.isBlank(settings().getWebhookSecret())) {
      throw new WebhookVerificationException("BTCPay webhook secret is not configured");
    }
  }

  private SubscriptionProperties.ProviderSettings settings() {
    return properties.providerSettings(PROVIDER_KEY);
  }

  private String baseUrl() {
    return StringUtils.stripEnd(
        StringUtils.defaultIfBlank(
                properties.providerSettings(PROVIDER_KEY).getBaseUrl(), "https://btcpay.bre.land")
            .trim(),
        "/");
  }

  private JsonNode firstNonNull(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && !node.isNull()) {
        return node;
      }
    }
    return null;
  }
}
