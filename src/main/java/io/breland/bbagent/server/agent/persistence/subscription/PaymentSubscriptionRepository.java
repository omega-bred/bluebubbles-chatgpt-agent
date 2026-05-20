package io.breland.bbagent.server.agent.persistence.subscription;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSubscriptionRepository
    extends JpaRepository<PaymentSubscriptionEntity, String> {
  List<PaymentSubscriptionEntity> findAllByAccountIdOrderByUpdatedAtDesc(String accountId);

  List<PaymentSubscriptionEntity> findAllByAccountIdAndStatusIn(
      String accountId, Collection<String> statuses);

  Optional<PaymentSubscriptionEntity> findFirstByAccountIdOrderByUpdatedAtDesc(String accountId);

  Optional<PaymentSubscriptionEntity> findByProviderAndProviderSubscriptionId(
      String provider, String providerSubscriptionId);

  Optional<PaymentSubscriptionEntity> findByProviderAndProviderCustomerSelector(
      String provider, String providerCustomerSelector);

  List<PaymentSubscriptionEntity> findByOrderByUpdatedAtDesc(Pageable pageable);
}
