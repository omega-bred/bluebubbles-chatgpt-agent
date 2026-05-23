package io.breland.bbagent.server.agent.persistence.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAccountRepository extends JpaRepository<AgentAccountEntity, String> {
  Optional<AgentAccountEntity> findByWebsiteSubject(String websiteSubject);

  Optional<AgentAccountEntity> findByWebsiteEmailIgnoreCase(String websiteEmail);

  List<AgentAccountEntity> findAllByProcessingBlockedTrueOrderByProcessingBlockedAtDesc(
      Pageable pageable);
}
