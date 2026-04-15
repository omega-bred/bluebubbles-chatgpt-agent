package io.breland.bbagent.server.agent.persistence.coder;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoderAsyncTaskStartRepository
    extends JpaRepository<CoderAsyncTaskStartEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from CoderAsyncTaskStartEntity s where s.idempotencyKey = :idempotencyKey")
  Optional<CoderAsyncTaskStartEntity> findLockedByIdempotencyKey(
      @Param("idempotencyKey") String idempotencyKey);
}
