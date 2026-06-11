package io.breland.bbagent.server.agent.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageIngressEventRepository
    extends JpaRepository<MessageIngressEventEntity, String> {
  List<MessageIngressEventEntity> findByStatus(String status);

  Optional<MessageIngressEventEntity> findByIdempotencyKey(String idempotencyKey);

  Optional<MessageIngressEventEntity> findFirstByStatusOrderByCreatedAtAsc(String status);

  long countByStatusIn(List<String> statuses);
}
