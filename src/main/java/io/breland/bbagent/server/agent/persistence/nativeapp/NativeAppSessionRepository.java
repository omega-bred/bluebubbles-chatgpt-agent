package io.breland.bbagent.server.agent.persistence.nativeapp;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NativeAppSessionRepository extends JpaRepository<NativeAppSessionEntity, String> {
  Optional<NativeAppSessionEntity> findByStartTokenHash(String startTokenHash);

  Optional<NativeAppSessionEntity> findByAppAccountToken(String appAccountToken);
}
