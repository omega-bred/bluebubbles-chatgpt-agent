package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.SubscriptionPlan;
import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentCheckoutSessionRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

class SubscriptionServiceTest {
  @Test
  void receiveWebhookStoresFailedEventWhenProviderProcessingFails() {
    SubscriptionProvider provider = mock(SubscriptionProvider.class);
    SubscriptionProviderRegistry registry = mock(SubscriptionProviderRegistry.class);
    PaymentProviderEventRepository eventRepository = mock(PaymentProviderEventRepository.class);
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    when(registry.require("btcpay")).thenReturn(provider);
    when(provider.providerKey()).thenReturn("btcpay");
    when(provider.verifyAndParseWebhook(headers, body))
        .thenReturn(
            new SubscriptionProvider.ProviderWebhookEvent(
                "event-1",
                "SubscriberUpdated",
                "account-1",
                null,
                null,
                "subscription-1",
                "BBAGENT_ACCOUNT_ID:account-1",
                "{}"));
    when(provider.customerSelector("account-1", "BBAGENT_ACCOUNT_ID:account-1"))
        .thenReturn("BBAGENT_ACCOUNT_ID:account-1");
    when(provider.fetchSubscription(any()))
        .thenThrow(new IllegalStateException("provider unavailable"));
    when(eventRepository.findByProviderAndProviderEventId("btcpay", "event-1"))
        .thenReturn(Optional.empty());
    when(eventRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    SubscriptionProviderWebhookResponse response =
        service(registry, eventRepository).receiveWebhook("btcpay", headers, body);

    ArgumentCaptor<PaymentProviderEventEntity> savedEvent =
        ArgumentCaptor.forClass(PaymentProviderEventEntity.class);
    verify(eventRepository).save(savedEvent.capture());
    assertThat(response.getStatus()).isEqualTo(SubscriptionStatuses.EVENT_FAILED);
    assertThat(response.getMessage()).isEqualTo("Webhook processing failed");
    assertThat(savedEvent.getValue().getStatus()).isEqualTo(SubscriptionStatuses.EVENT_FAILED);
    assertThat(savedEvent.getValue().getErrorMessage()).isEqualTo("provider unavailable");
  }

  @Test
  void listPlansUsesProviderSpecificPrices() {
    SubscriptionProviderRegistry registry = registryWith("stripe", "btcpay");
    SubscriptionProperties properties = propertiesWithProviderPrices();

    List<SubscriptionPlan> plans =
        service(properties, registry, mock(PaymentProviderEventRepository.class))
            .listPlans()
            .getPlans();

    assertThat(plans)
        .extracting(SubscriptionPlan::getProvider, SubscriptionPlan::getPriceAmount)
        .containsExactly(tuple("stripe", "5"), tuple("btcpay", "4"));
  }

  @Test
  void adminStatsUseProviderSpecificMonthlyPrices() {
    SubscriptionProperties properties = propertiesWithProviderPrices();
    PaymentSubscriptionRepository subscriptionRepository =
        mock(PaymentSubscriptionRepository.class);
    Instant now = Instant.now();
    PaymentSubscriptionEntity stripeSubscription =
        subscription("subscription-stripe", "account-stripe", "stripe", now);
    PaymentSubscriptionEntity btcpaySubscription =
        subscription("subscription-btcpay", "account-btcpay", "btcpay", now);
    when(subscriptionRepository.findByOrderByUpdatedAtDesc(any()))
        .thenReturn(List.of(stripeSubscription, btcpaySubscription));
    when(subscriptionRepository.findAll())
        .thenReturn(List.of(stripeSubscription, btcpaySubscription));

    String monthlyRecurringAmount =
        new SubscriptionService(
                properties,
                mock(SubscriptionProviderRegistry.class),
                mock(AgentAccountResolver.class),
                mock(AgentAccountRepository.class),
                mock(PaymentCheckoutSessionRepository.class),
                subscriptionRepository,
                mock(PaymentProviderEventRepository.class),
                null)
            .adminListSubscriptions(100)
            .getStats()
            .getMonthlyRecurringAmount();

    assertThat(monthlyRecurringAmount).isEqualTo("9");
  }

  private SubscriptionService service(
      SubscriptionProviderRegistry registry, PaymentProviderEventRepository eventRepository) {
    return service(properties(), registry, eventRepository);
  }

  private SubscriptionService service(
      SubscriptionProperties properties,
      SubscriptionProviderRegistry registry,
      PaymentProviderEventRepository eventRepository) {
    return new SubscriptionService(
        properties,
        registry,
        mock(AgentAccountResolver.class),
        mock(AgentAccountRepository.class),
        mock(PaymentCheckoutSessionRepository.class),
        mock(PaymentSubscriptionRepository.class),
        eventRepository,
        null);
  }

  private SubscriptionProperties properties() {
    SubscriptionProperties properties = new SubscriptionProperties();
    SubscriptionProperties.Plan plan = new SubscriptionProperties.Plan();
    SubscriptionProperties.ProviderPlan providerPlan = new SubscriptionProperties.ProviderPlan();
    providerPlan.setOfferingId("offering-1");
    providerPlan.setPlanId("plan-1");
    plan.getProviders().put("btcpay", providerPlan);
    properties.setPlans(List.of(plan));
    return properties;
  }

  private SubscriptionProperties propertiesWithProviderPrices() {
    SubscriptionProperties properties = new SubscriptionProperties();
    properties.setDefaultProvider("stripe");
    SubscriptionProperties.Plan plan = new SubscriptionProperties.Plan();
    plan.setKey("premium_monthly");
    plan.setPriceAmount(new BigDecimal("5.00"));

    SubscriptionProperties.ProviderPlan btcpayPlan = new SubscriptionProperties.ProviderPlan();
    btcpayPlan.setOfferingId("offering-btcpay");
    btcpayPlan.setPlanId("plan-btcpay");
    btcpayPlan.setPriceAmount(new BigDecimal("4.00"));
    btcpayPlan.setCurrency("USD");
    btcpayPlan.setBillingInterval("monthly");

    SubscriptionProperties.ProviderPlan stripePlan = new SubscriptionProperties.ProviderPlan();
    stripePlan.setOfferingId("prod-stripe");
    stripePlan.setPlanId("price-stripe");
    stripePlan.setPriceAmount(new BigDecimal("5.00"));
    stripePlan.setCurrency("USD");
    stripePlan.setBillingInterval("monthly");

    plan.getProviders().put("btcpay", btcpayPlan);
    plan.getProviders().put("stripe", stripePlan);
    properties.setPlans(List.of(plan));
    return properties;
  }

  private SubscriptionProviderRegistry registryWith(
      String defaultProviderKey, String otherProviderKey) {
    SubscriptionProviderRegistry registry = mock(SubscriptionProviderRegistry.class);
    SubscriptionProvider defaultProvider = mockProvider(defaultProviderKey);
    SubscriptionProvider otherProvider = mockProvider(otherProviderKey);
    when(registry.require(defaultProviderKey)).thenReturn(defaultProvider);
    when(registry.require(otherProviderKey)).thenReturn(otherProvider);
    when(registry.providerKeys()).thenReturn(List.of(otherProviderKey, defaultProviderKey));
    return registry;
  }

  private SubscriptionProvider mockProvider(String providerKey) {
    SubscriptionProvider provider = mock(SubscriptionProvider.class);
    when(provider.providerKey()).thenReturn(providerKey);
    return provider;
  }

  private PaymentSubscriptionEntity subscription(
      String subscriptionId, String accountId, String provider, Instant now) {
    PaymentSubscriptionEntity subscription =
        new PaymentSubscriptionEntity(
            subscriptionId,
            accountId,
            provider,
            "premium_monthly",
            "customer-" + accountId,
            SubscriptionStatuses.SUBSCRIPTION_ACTIVE,
            now,
            now);
    subscription.setCurrentPeriodEnd(now.plusSeconds(2_592_000));
    return subscription;
  }
}
