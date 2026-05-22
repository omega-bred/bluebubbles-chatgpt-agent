package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentCheckoutSessionRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionRepository;
import java.nio.charset.StandardCharsets;
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

  private SubscriptionService service(
      SubscriptionProviderRegistry registry, PaymentProviderEventRepository eventRepository) {
    return new SubscriptionService(
        properties(),
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
}
