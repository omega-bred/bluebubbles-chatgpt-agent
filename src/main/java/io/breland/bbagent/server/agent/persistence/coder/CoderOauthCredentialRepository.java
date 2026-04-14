package io.breland.bbagent.server.agent.persistence.coder;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoderOauthCredentialRepository
    extends JpaRepository<CoderOauthCredentialEntity, String> {
  Optional<CoderOauthCredentialEntity> findFirstByAccountBaseEndingWithOrderByUpdatedAtDesc(
      String suffix);
}
