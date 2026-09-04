package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.SubscriptionProviderWebhookResponse;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentProviderEventRepository;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionEntity;
import io.breland.bbagent.server.agent.persistence.subscription.PaymentSubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class SubscriptionConcurrencyIntegrationTest {
  @Autowired private SubscriptionService subscriptionService;
  @Autowired private AgentAccountRepository accountRepository;
  @Autowired private PaymentSubscriptionRepository subscriptionRepository;
  @Autowired private PaymentProviderEventRepository eventRepository;
  @MockitoBean private SubscriptionProviderRegistry providerRegistry;

  @Test
  void concurrentWebhookEventsShareOneProviderSubscription() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String accountId = suffix;
    String providerSubscriptionId = "provider-subscription-" + suffix;
    String customerSelector = "cus_" + suffix;
    Instant now = Instant.now();
    accountRepository.saveAndFlush(new AgentAccountEntity(accountId, now, now));

    SubscriptionProvider provider = mock(SubscriptionProvider.class);
    when(providerRegistry.require("stripe")).thenReturn(provider);
    when(provider.providerKey()).thenReturn("stripe");
    when(provider.verifyAndParseWebhook(any(HttpHeaders.class), any(byte[].class)))
        .thenAnswer(
            invocation -> {
              String marker = new String(invocation.getArgument(1), StandardCharsets.UTF_8);
              String eventType =
                  "checkout".equals(marker)
                      ? "checkout.session.completed"
                      : "customer.subscription.created";
              return new SubscriptionProvider.ProviderWebhookEvent(
                  "event-" + marker + "-" + suffix,
                  eventType,
                  accountId,
                  null,
                  null,
                  providerSubscriptionId,
                  customerSelector,
                  "{}");
            });
    when(provider.customerSelector(accountId, customerSelector)).thenReturn(customerSelector);

    CyclicBarrier concurrentProviderFetches = new CyclicBarrier(2);
    SubscriptionProvider.ProviderSubscription providerSubscription =
        new SubscriptionProvider.ProviderSubscription(
            providerSubscriptionId,
            customerSelector,
            customerSelector,
            SubscriptionStatuses.SUBSCRIPTION_TRIALING,
            SubscriptionStatuses.SUBSCRIPTION_TRIALING,
            now,
            now.plusSeconds(2_592_000),
            null,
            null,
            null,
            false,
            null,
            "{}");
    when(provider.fetchSubscription(any()))
        .thenAnswer(
            invocation -> {
              concurrentProviderFetches.await(10, TimeUnit.SECONDS);
              return providerSubscription;
            });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<SubscriptionProviderWebhookResponse> checkoutEvent =
          executor.submit(
              () ->
                  subscriptionService.receiveWebhook(
                      "stripe", new HttpHeaders(), "checkout".getBytes(StandardCharsets.UTF_8)));
      Future<SubscriptionProviderWebhookResponse> subscriptionEvent =
          executor.submit(
              () ->
                  subscriptionService.receiveWebhook(
                      "stripe",
                      new HttpHeaders(),
                      "subscription".getBytes(StandardCharsets.UTF_8)));

      assertThat(checkoutEvent.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(SubscriptionStatuses.EVENT_PROCESSED);
      assertThat(subscriptionEvent.get(15, TimeUnit.SECONDS).getStatus())
          .isEqualTo(SubscriptionStatuses.EVENT_PROCESSED);
    } finally {
      executor.shutdownNow();
    }

    List<PaymentSubscriptionEntity> subscriptions =
        subscriptionRepository.findAllByAccountIdOrderByUpdatedAtDesc(accountId);
    assertThat(subscriptions).hasSize(1);
    String subscriptionId = subscriptions.getFirst().getSubscriptionId();
    assertThat(
            eventRepository
                .findByProviderAndProviderEventId("stripe", "event-checkout-" + suffix)
                .orElseThrow()
                .getSubscriptionId())
        .isEqualTo(subscriptionId);
    assertThat(
            eventRepository
                .findByProviderAndProviderEventId("stripe", "event-subscription-" + suffix)
                .orElseThrow()
                .getSubscriptionId())
        .isEqualTo(subscriptionId);
  }
}
