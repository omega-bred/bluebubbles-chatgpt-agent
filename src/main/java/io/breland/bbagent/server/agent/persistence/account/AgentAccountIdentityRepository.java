package io.breland.bbagent.server.agent.persistence.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAccountIdentityRepository
    extends JpaRepository<AgentAccountIdentityEntity, String> {
  Optional<AgentAccountIdentityEntity> findByIdentityTypeAndNormalizedIdentifier(
      String identityType, String normalizedIdentifier);

  Optional<AgentAccountIdentityEntity> findByIdentityTypeAndNormalizedIdentifierAndAccountId(
      String identityType, String normalizedIdentifier, String accountId);

  List<AgentAccountIdentityEntity> findAllByAccountIdOrderByCreatedAtAsc(String accountId);
}
