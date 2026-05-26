package io.breland.bbagent.server.agent.persistence.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentAccountRepository extends JpaRepository<AgentAccountEntity, String> {
  Optional<AgentAccountEntity> findByWebsiteSubject(String websiteSubject);

  Optional<AgentAccountEntity> findByWebsiteEmailIgnoreCase(String websiteEmail);

  List<AgentAccountEntity> findAllByProcessingBlockedTrueOrderByProcessingBlockedAtDesc(
      Pageable pageable);

  @Query(
      """
      select account
      from AgentAccountEntity account
      where account.canaryAccount = true
        and (
          (account.canaryLastSeenAt is not null and account.canaryLastSeenAt < :cutoff)
          or (account.canaryLastSeenAt is null and account.updatedAt < :cutoff)
        )
      """)
  List<AgentAccountEntity> findExpiredCanaryAccounts(@Param("cutoff") java.time.Instant cutoff);
}
