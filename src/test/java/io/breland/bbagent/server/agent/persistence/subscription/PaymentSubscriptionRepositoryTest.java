package io.breland.bbagent.server.agent.persistence.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PaymentSubscriptionRepositoryTest {
  @Autowired private AgentAccountRepository accountRepository;
  @Autowired private PaymentSubscriptionRepository subscriptionRepository;

  @Test
  void duplicateProviderSubscriptionIdsAreRejected() {
    Instant now = Instant.parse("2026-08-31T16:00:00Z");
    String accountId = UUID.randomUUID().toString();
    String providerSubscriptionId = "provider-subscription-" + UUID.randomUUID();
    accountRepository.save(new AgentAccountEntity(accountId, now, now));

    PaymentSubscriptionEntity older =
        subscription(
            accountId,
            providerSubscriptionId,
            "customer-" + UUID.randomUUID(),
            now.minusSeconds(30));
    PaymentSubscriptionEntity newer =
        subscription(accountId, providerSubscriptionId, "customer-" + UUID.randomUUID(), now);
    subscriptionRepository.saveAndFlush(older);

    assertThatThrownBy(() -> subscriptionRepository.saveAndFlush(newer))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void duplicateCustomerSelectorsReturnTheMostRecentlyUpdatedSubscription() {
    Instant now = Instant.parse("2026-08-31T16:00:00Z");
    String accountId = UUID.randomUUID().toString();
    String customerSelector = "customer-" + UUID.randomUUID();
    accountRepository.save(new AgentAccountEntity(accountId, now, now));

    PaymentSubscriptionEntity older =
        subscription(
            accountId,
            "provider-subscription-" + UUID.randomUUID(),
            customerSelector,
            now.minusSeconds(30));
    PaymentSubscriptionEntity newer =
        subscription(
            accountId, "provider-subscription-" + UUID.randomUUID(), customerSelector, now);
    subscriptionRepository.saveAllAndFlush(List.of(older, newer));

    assertThat(
            subscriptionRepository.findByProviderAndProviderCustomerSelector(
                "stripe", customerSelector))
        .get()
        .extracting(PaymentSubscriptionEntity::getSubscriptionId)
        .isEqualTo(newer.getSubscriptionId());
  }

  private static PaymentSubscriptionEntity subscription(
      String accountId, String providerSubscriptionId, String customerSelector, Instant updatedAt) {
    PaymentSubscriptionEntity subscription =
        new PaymentSubscriptionEntity(
            UUID.randomUUID().toString(),
            accountId,
            "stripe",
            "premium",
            customerSelector,
            "trialing",
            updatedAt.minusSeconds(60),
            updatedAt);
    subscription.setProviderSubscriptionId(providerSubscriptionId);
    return subscription;
  }
}
