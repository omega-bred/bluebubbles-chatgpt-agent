package io.breland.bbagent.server.agent.persistence.subscription;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentSubscriptionRepository
    extends JpaRepository<PaymentSubscriptionEntity, String> {
  List<PaymentSubscriptionEntity> findAllByAccountIdOrderByUpdatedAtDesc(String accountId);

  List<PaymentSubscriptionEntity> findAllByAccountIdAndStatusIn(
      String accountId, Collection<String> statuses);

  Optional<PaymentSubscriptionEntity> findFirstByAccountIdOrderByUpdatedAtDesc(String accountId);

  Optional<PaymentSubscriptionEntity> findByProviderAndProviderSubscriptionId(
      String provider, String providerSubscriptionId);

  @Query(
      value =
          """
          SELECT * FROM payment_subscriptions
          WHERE provider = :provider AND provider_customer_selector = :providerCustomerSelector
          ORDER BY updated_at DESC, subscription_id DESC
          LIMIT 1
          """,
      nativeQuery = true)
  Optional<PaymentSubscriptionEntity> findByProviderAndProviderCustomerSelector(
      @Param("provider") String provider,
      @Param("providerCustomerSelector") String providerCustomerSelector);

  List<PaymentSubscriptionEntity> findByOrderByUpdatedAtDesc(Pageable pageable);
}
