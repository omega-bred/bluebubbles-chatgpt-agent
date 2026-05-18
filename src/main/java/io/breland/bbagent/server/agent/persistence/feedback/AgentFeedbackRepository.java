package io.breland.bbagent.server.agent.persistence.feedback;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentFeedbackRepository extends JpaRepository<AgentFeedbackEntity, String> {

  List<AgentFeedbackEntity> findAllByOrderBySubmittedAtDesc(Pageable pageable);

  List<AgentFeedbackEntity> findAllByReadAtIsNullOrderBySubmittedAtDesc(Pageable pageable);

  List<AgentFeedbackEntity> findAllByReadAtIsNotNullOrderBySubmittedAtDesc(Pageable pageable);

  long countByReadAtIsNull();

  long countByReadAtIsNotNull();
}
