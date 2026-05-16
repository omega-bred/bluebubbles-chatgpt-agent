package io.breland.bbagent.server.agent.persistence.metrics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageMetricRepository
    extends JpaRepository<AgentMessageMetricEntity, String> {

  @Query(
      """
      select count(metric) as messageCount,
             count(distinct metric.userKeyHash) as activeUsers
      from AgentMessageMetricEntity metric
      where metric.occurredAt >= :from and metric.occurredAt < :to
      """)
  TotalProjection summarize(@Param("from") Instant from, @Param("to") Instant to);

  @Query(
      """
      select metric.modelKey as modelKey,
             metric.modelLabel as modelLabel,
             metric.responsesModel as responsesModel,
             metric.plan as plan,
             metric.premium as premium,
             count(metric) as messageCount,
             count(distinct metric.userKeyHash) as activeUsers
      from AgentMessageMetricEntity metric
      where metric.occurredAt >= :from and metric.occurredAt < :to
      group by metric.modelKey,
               metric.modelLabel,
               metric.responsesModel,
               metric.plan,
               metric.premium
      order by count(metric) desc
      """)
  List<ModelProjection> summarizeByModel(@Param("from") Instant from, @Param("to") Instant to);

  List<AgentMessageMetricEntity>
      findAllByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
          Instant from, Instant to);

  interface TotalProjection {
    Long getMessageCount();

    Long getActiveUsers();
  }

  interface ModelProjection {
    String getModelKey();

    String getModelLabel();

    String getResponsesModel();

    String getPlan();

    Boolean getPremium();

    Long getMessageCount();

    Long getActiveUsers();
  }
}
