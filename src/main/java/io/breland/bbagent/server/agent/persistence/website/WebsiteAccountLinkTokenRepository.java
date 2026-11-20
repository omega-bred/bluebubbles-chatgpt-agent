package io.breland.bbagent.server.agent.persistence.website;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WebsiteAccountLinkTokenRepository
    extends JpaRepository<WebsiteAccountLinkTokenEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select token from WebsiteAccountLinkTokenEntity token where token.tokenHash = :tokenHash")
  Optional<WebsiteAccountLinkTokenEntity> findForRedemption(String tokenHash);
}
