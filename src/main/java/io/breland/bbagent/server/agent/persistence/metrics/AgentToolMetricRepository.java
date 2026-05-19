package io.breland.bbagent.server.agent.persistence.metrics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentToolMetricRepository extends JpaRepository<AgentToolMetricEntity, String> {

  @Query(
      """
      select count(metric) as toolCallCount,
             sum(case when metric.success = true then 1 else 0 end) as successfulToolCalls,
             sum(case when metric.success = false then 1 else 0 end) as failedToolCalls,
             count(distinct metric.userKeyHash) as activeUsers
      from AgentToolMetricEntity metric
      where metric.occurredAt >= :from and metric.occurredAt < :to
      """)
  TotalProjection summarize(@Param("from") Instant from, @Param("to") Instant to);

  @Query(
      """
      select metric.toolName as toolName,
             metric.toolCategory as toolCategory,
             count(metric) as callCount,
             sum(case when metric.success = true then 1 else 0 end) as successfulCalls,
             sum(case when metric.success = false then 1 else 0 end) as failedCalls,
             count(distinct metric.userKeyHash) as activeUsers,
             avg(metric.durationMillis) as averageDurationMs,
             max(metric.occurredAt) as lastUsedAt
      from AgentToolMetricEntity metric
      where metric.occurredAt >= :from and metric.occurredAt < :to
      group by metric.toolName,
               metric.toolCategory
      order by count(metric) desc
      """)
  List<ToolProjection> summarizeByTool(@Param("from") Instant from, @Param("to") Instant to);

  @Query(
      """
      select metric.accountType as accountType,
             metric.plan as plan,
             metric.premium as premium,
             count(metric) as callCount,
             sum(case when metric.success = true then 1 else 0 end) as successfulCalls,
             sum(case when metric.success = false then 1 else 0 end) as failedCalls,
             count(distinct metric.userKeyHash) as activeUsers
      from AgentToolMetricEntity metric
      where metric.occurredAt >= :from and metric.occurredAt < :to
      group by metric.accountType,
               metric.plan,
               metric.premium
      order by count(metric) desc
      """)
  List<AccountTypeProjection> summarizeByAccountType(
      @Param("from") Instant from, @Param("to") Instant to);

  interface TotalProjection {
    Long getToolCallCount();

    Long getSuccessfulToolCalls();

    Long getFailedToolCalls();

    Long getActiveUsers();
  }

  interface ToolProjection {
    String getToolName();

    String getToolCategory();

    Long getCallCount();

    Long getSuccessfulCalls();

    Long getFailedCalls();

    Long getActiveUsers();

    Double getAverageDurationMs();

    Instant getLastUsedAt();
  }

  interface AccountTypeProjection {
    String getAccountType();

    String getPlan();

    Boolean getPremium();

    Long getCallCount();

    Long getSuccessfulCalls();

    Long getFailedCalls();

    Long getActiveUsers();
  }
}
