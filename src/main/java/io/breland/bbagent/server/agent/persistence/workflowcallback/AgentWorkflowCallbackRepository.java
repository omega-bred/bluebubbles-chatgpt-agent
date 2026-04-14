package io.breland.bbagent.server.agent.persistence.workflowcallback;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentWorkflowCallbackRepository
    extends JpaRepository<AgentWorkflowCallbackEntity, String> {
  List<AgentWorkflowCallbackEntity> findByStatusAndExpiresAtBefore(String status, Instant instant);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from AgentWorkflowCallbackEntity c where c.callbackId = :callbackId")
  Optional<AgentWorkflowCallbackEntity> findLockedByCallbackId(
      @Param("callbackId") String callbackId);
}
