package io.breland.bbagent.server.subscriptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
public class StripeSubscriptionProvider implements SubscriptionProvider {
  public static final String PROVIDER_KEY = "stripe";
  private static final String SIGNATURE_HEADER = "Stripe-Signature";

  private final ObjectMapper objectMapper;
  private final SubscriptionProperties properties;

  public StripeSubscriptionProvider(ObjectMapper objectMapper, SubscriptionProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Override
  public String providerKey() {
    return PROVIDER_KEY;
  }

  @Override
  public ProviderCheckoutSession createCheckout(CheckoutRequest request) {
    ensureConfigured();
    ensureProviderPlanConfigured(request.providerPlan());
    Map<String, String> metadata = metadata(request);
    com.stripe.param.checkout.SessionCreateParams.Builder builder =
        com.stripe.param.checkout.SessionCreateParams.builder()
            .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
            .setClientReferenceId(request.accountId())
            .setSuccessUrl(request.returnUrl())
            .setCancelUrl(request.returnUrl())
            .addLineItem(
                com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                    .setPrice(request.providerPlan().getPlanId())
                    .setQuantity(1L)
                    .build());
    metadata.forEach(builder::putMetadata);
    com.stripe.param.checkout.SessionCreateParams.SubscriptionData.Builder subscriptionData =
        com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder();
    metadata.forEach(subscriptionData::putMetadata);
    builder.setSubscriptionData(subscriptionData.build());
    if (StringUtils.isNotBlank(request.email())) {
      builder.setCustomerEmail(request.email());
    }
    if (request.durationMinutes() >= 30) {
      builder.setExpiresAt(
          Instant.now().plusSeconds(request.durationMinutes() * 60L).getEpochSecond());
    }
    if (settings().isAutomaticTaxEnabled()) {
      builder.setAutomaticTax(
          com.stripe.param.checkout.SessionCreateParams.AutomaticTax.builder()
              .setEnabled(true)
              .build());
    }

    try {
      Session session =
          client()
              .v1()
              .checkout()
              .sessions()
              .create(
                  builder.build(),
                  RequestOptions.builder()
                      .setIdempotencyKey("bbagent-checkout-" + request.internalCheckoutSessionId())
                      .build());
      return new ProviderCheckoutSession(
          session.getId(),
          session.getUrl(),
          session.getCustomer(),
          epoch(session.getExpiresAt()),
          json(session));
    } catch (StripeException e) {
      throw asProviderException("create checkout", e);
    }
  }

  @Override
  public ProviderPortalSession createPortalSession(PortalRequest request) {
    ensureConfigured();
    String customerId = StringUtils.trimToNull(request.customerSelector());
    if (StringUtils.isBlank(customerId) || !customerId.startsWith("cus_")) {
      throw new IllegalStateException("Stripe customer is not available for this subscription");
    }
    SessionCreateParams.Builder builder =
        SessionCreateParams.builder().setCustomer(customerId).setReturnUrl(request.returnUrl());
    if (StringUtils.isNotBlank(settings().getPortalConfigurationId())) {
      builder.setConfiguration(settings().getPortalConfigurationId());
    }
    try {
      com.stripe.model.billingportal.Session session =
          client()
              .v1()
              .billingPortal()
              .sessions()
              .create(
                  builder.build(),
                  RequestOptions.builder()
                      .setIdempotencyKey("bbagent-portal-" + request.accountId())
                      .build());
      return new ProviderPortalSession(session.getId(), session.getUrl(), json(session));
    } catch (StripeException e) {
      throw asProviderException("create portal", e);
    }
  }

  @Override
  public ProviderSubscription fetchSubscription(SubscriptionLookup lookup) {
    ensureConfigured();
    ensureProviderPlanConfigured(lookup.providerPlan());
    try {
      Subscription subscription =
          StringUtils.isNotBlank(lookup.providerSubscriptionId())
              ? client().v1().subscriptions().retrieve(lookup.providerSubscriptionId())
              : firstSubscriptionForCustomer(lookup);
      return toProviderSubscription(lookup, subscription);
    } catch (StripeException e) {
      throw asProviderException("fetch subscription", e);
    }
  }

  @Override
  public ProviderSubscription suspendSubscription(SubscriptionLookup lookup, String reason) {
    return updateCancelAtPeriodEnd(lookup, true, "suspend");
  }

  @Override
  public ProviderSubscription unsuspendSubscription(SubscriptionLookup lookup) {
    return updateCancelAtPeriodEnd(lookup, false, "unsuspend");
  }

  @Override
  public ProviderWebhookEvent verifyAndParseWebhook(HttpHeaders headers, byte[] body) {
    ensureWebhookConfigured();
    byte[] payload = body == null ? new byte[0] : body;
    String rawPayload = new String(payload, StandardCharsets.UTF_8);
    Event event;
    try {
      event =
          Webhook.constructEvent(
              rawPayload, headers.getFirst(SIGNATURE_HEADER), settings().getWebhookSecret());
    } catch (SignatureVerificationException e) {
      throw new WebhookVerificationException("Invalid Stripe webhook signature", e);
    } catch (Exception e) {
      throw new WebhookVerificationException("Invalid Stripe webhook payload", e);
    }

    JsonNode payloadNode;
    try {
      payloadNode = objectMapper.readTree(payload);
    } catch (Exception e) {
      throw new WebhookVerificationException("Invalid Stripe webhook payload", e);
    }
    JsonNode object = payloadNode.path("data").path("object");
    String eventType = firstNonBlank(event.getType(), firstText(payloadNode, "type"), "unknown");
    String providerEventId =
        firstNonBlank(
            event.getId(),
            firstText(payloadNode, "id"),
            eventType + ":" + DigestUtils.sha256Hex(rawPayload));
    String providerCheckoutId = null;
    String checkoutSessionId = metadataValue(object, "bbagent_checkout_id");
    String providerSubscriptionId = null;
    String customerSelector = firstText(object, "customer");

    if ("checkout.session.completed".equals(eventType)) {
      providerCheckoutId = firstText(object, "id");
      providerSubscriptionId = firstText(object, "subscription");
    } else if (eventType.startsWith("customer.subscription.")) {
      providerSubscriptionId = firstText(object, "id");
    } else if (StringUtils.isNotBlank(firstText(object, "subscription"))) {
      providerSubscriptionId = firstText(object, "subscription");
    }

    return new ProviderWebhookEvent(
        providerEventId,
        eventType,
        metadataValue(object, "bbagent_account_id"),
        checkoutSessionId,
        providerCheckoutId,
        providerSubscriptionId,
        customerSelector,
        rawPayload);
  }

  @Override
  public String customerSelector(String accountId, String existing) {
    return StringUtils.isNotBlank(existing) ? existing : accountId;
  }

  private ProviderSubscription updateCancelAtPeriodEnd(
      SubscriptionLookup lookup, boolean cancelAtPeriodEnd, String operation) {
    ensureConfigured();
    if (StringUtils.isBlank(lookup.providerSubscriptionId())) {
      throw new IllegalStateException("Stripe subscription id is not available");
    }
    try {
      Subscription subscription =
          client()
              .v1()
              .subscriptions()
              .update(
                  lookup.providerSubscriptionId(),
                  SubscriptionUpdateParams.builder()
                      .setCancelAtPeriodEnd(cancelAtPeriodEnd)
                      .build(),
                  RequestOptions.builder()
                      .setIdempotencyKey(
                          "bbagent-stripe-" + operation + "-" + lookup.providerSubscriptionId())
                      .build());
      return toProviderSubscription(lookup, subscription);
    } catch (StripeException e) {
      throw asProviderException(operation + " subscription", e);
    }
  }

  private Subscription firstSubscriptionForCustomer(SubscriptionLookup lookup)
      throws StripeException {
    String customerId = StringUtils.trimToNull(lookup.customerSelector());
    if (StringUtils.isBlank(customerId) || !customerId.startsWith("cus_")) {
      throw new IllegalStateException("Stripe customer is not available for this subscription");
    }
    SubscriptionListParams params =
        SubscriptionListParams.builder()
            .setCustomer(customerId)
            .setPrice(lookup.providerPlan().getPlanId())
            .setStatus(SubscriptionListParams.Status.ALL)
            .setLimit(1L)
            .build();
    return client().v1().subscriptions().list(params).getData().stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Stripe subscription not found"));
  }

  private ProviderSubscription toProviderSubscription(
      SubscriptionLookup lookup, Subscription subscription) {
    String customerId = firstNonBlank(subscription.getCustomer(), lookup.customerSelector());
    String providerSubscriptionId =
        firstNonBlank(subscription.getId(), lookup.providerSubscriptionId());
    return new ProviderSubscription(
        providerSubscriptionId,
        customerId,
        firstNonBlank(customerId, lookup.customerSelector()),
        subscription.getStatus(),
        normalizedStatus(subscription),
        epoch(currentPeriodStart(subscription)),
        epoch(currentPeriodEnd(subscription)),
        epoch(subscription.getTrialEnd()),
        null,
        epoch(firstNonNull(subscription.getCanceledAt(), subscription.getEndedAt())),
        Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()),
        null,
        json(subscription));
  }

  private Long currentPeriodStart(Subscription subscription) {
    return subscription.getItems() == null || subscription.getItems().getData() == null
        ? null
        : subscription.getItems().getData().stream()
            .map(item -> item.getCurrentPeriodStart())
            .filter(value -> value != null)
            .min(Long::compareTo)
            .orElse(null);
  }

  private Long currentPeriodEnd(Subscription subscription) {
    return subscription.getItems() == null || subscription.getItems().getData() == null
        ? subscription.getCancelAt()
        : subscription.getItems().getData().stream()
            .map(item -> item.getCurrentPeriodEnd())
            .filter(value -> value != null)
            .max(Long::compareTo)
            .orElse(subscription.getCancelAt());
  }

  private String normalizedStatus(Subscription subscription) {
    String status = StringUtils.lowerCase(StringUtils.trimToEmpty(subscription.getStatus()));
    return switch (status) {
      case "active" -> SubscriptionStatuses.SUBSCRIPTION_ACTIVE;
      case "trialing" -> SubscriptionStatuses.SUBSCRIPTION_TRIALING;
      case "past_due", "unpaid" -> SubscriptionStatuses.SUBSCRIPTION_PAST_DUE;
      case "canceled", "incomplete_expired" -> SubscriptionStatuses.SUBSCRIPTION_CANCELED;
      case "paused" -> SubscriptionStatuses.SUBSCRIPTION_SUSPENDED;
      case "incomplete" -> SubscriptionStatuses.SUBSCRIPTION_PENDING;
      default -> SubscriptionStatuses.SUBSCRIPTION_UNKNOWN;
    };
  }

  private static Map<String, String> metadata(CheckoutRequest request) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("bbagent_account_id", request.accountId());
    metadata.put("bbagent_checkout_id", request.internalCheckoutSessionId());
    metadata.put("bbagent_plan_key", request.plan().getKey());
    metadata.put("bbagent_provider", PROVIDER_KEY);
    return metadata;
  }

  private String metadataValue(JsonNode node, String key) {
    JsonNode metadata = findObject(node, "metadata");
    if (metadata == null || !metadata.isObject()) {
      return null;
    }
    return firstText(metadata, key);
  }

  private JsonNode findObject(JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode direct = node.get(fieldName);
    if (direct != null && direct.isObject()) {
      return direct;
    }
    if (node.isObject()) {
      for (JsonNode value : node) {
        JsonNode found = findObject(value, fieldName);
        if (found != null) {
          return found;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        JsonNode found = findObject(item, fieldName);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private String firstText(JsonNode node, String... fieldNames) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value != null && !value.isNull()) {
        String text = value.isTextual() ? value.asText() : value.asText(null);
        if (StringUtils.isNotBlank(text)) {
          return text;
        }
      }
    }
    return null;
  }

  private StripeClient client() {
    return new StripeClient(settings().getApiKey());
  }

  private SubscriptionProperties.ProviderSettings settings() {
    return properties.providerSettings(PROVIDER_KEY);
  }

  private void ensureConfigured() {
    if (!settings().isEnabled() || StringUtils.isBlank(settings().getApiKey())) {
      throw new IllegalStateException("Stripe subscription provider is not configured");
    }
    if (settings().isTestModeOnly() && !isTestModeKey(settings().getApiKey())) {
      throw new IllegalStateException("Stripe subscription provider requires a test-mode API key");
    }
  }

  private boolean isTestModeKey(String apiKey) {
    String key = StringUtils.trimToEmpty(apiKey);
    return key.startsWith("rk_test_") || key.startsWith("sk_test_");
  }

  private void ensureProviderPlanConfigured(SubscriptionProperties.ProviderPlan providerPlan) {
    if (providerPlan == null || StringUtils.isBlank(providerPlan.getPlanId())) {
      throw new IllegalStateException("Stripe subscription price is not configured");
    }
  }

  private void ensureWebhookConfigured() {
    if (StringUtils.isBlank(settings().getWebhookSecret())) {
      throw new WebhookVerificationException("Stripe webhook secret is not configured");
    }
  }

  private RestClientException asProviderException(String operation, StripeException e) {
    return new RestClientException("Stripe " + operation + " failed: " + e.getMessage(), e);
  }

  private Instant epoch(Long value) {
    return value == null ? null : Instant.ofEpochSecond(value);
  }

  private Long firstNonNull(Long... values) {
    for (Long value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  private String json(Object object) {
    try {
      return ApiResource.GSON.toJson(object);
    } catch (Exception e) {
      return "{}";
    }
  }
}
