package io.breland.bbagent.server.agent.persistence.appclip;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppClipSessionRepository extends JpaRepository<AppClipSessionEntity, String> {
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          update app_clip_sessions
          set token_hash = :replacementHash, last_used_at = :now
          where token_hash = :tokenHash and account_id = :accountId
            and revoked_at is null and expires_at > :now
          """,
      nativeQuery = true)
  int rotateToken(String tokenHash, String replacementHash, String accountId, Instant now);
}
