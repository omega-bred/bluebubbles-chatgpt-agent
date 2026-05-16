package io.breland.bbagent.server.agent.persistence.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAccountRepository extends JpaRepository<AgentAccountEntity, String> {
  Optional<AgentAccountEntity> findByWebsiteSubject(String websiteSubject);
}
