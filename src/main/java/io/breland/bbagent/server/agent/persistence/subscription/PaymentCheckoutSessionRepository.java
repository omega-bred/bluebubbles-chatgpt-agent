package io.breland.bbagent.server.agent.persistence.subscription;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCheckoutSessionRepository
    extends JpaRepository<PaymentCheckoutSessionEntity, String> {
  Optional<PaymentCheckoutSessionEntity> findByProviderAndProviderCheckoutId(
      String provider, String providerCheckoutId);

  List<PaymentCheckoutSessionEntity> findAllByAccountIdOrderByCreatedAtDesc(String accountId);
}
