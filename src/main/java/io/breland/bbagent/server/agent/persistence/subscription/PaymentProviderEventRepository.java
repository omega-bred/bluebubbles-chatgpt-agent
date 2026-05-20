package io.breland.bbagent.server.agent.persistence.subscription;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentProviderEventRepository
    extends JpaRepository<PaymentProviderEventEntity, String> {
  Optional<PaymentProviderEventEntity> findByProviderAndProviderEventId(
      String provider, String providerEventId);
}
