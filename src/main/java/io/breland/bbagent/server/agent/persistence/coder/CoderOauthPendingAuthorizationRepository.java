package io.breland.bbagent.server.agent.persistence.coder;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CoderOauthPendingAuthorizationRepository
    extends JpaRepository<CoderOauthPendingAuthorizationEntity, String> {
  @Transactional
  long deleteByExpiresAtBefore(Instant instant);
}
