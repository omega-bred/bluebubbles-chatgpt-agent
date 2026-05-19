package io.breland.bbagent.server.metrics;

import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricEntity;
import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricRepository;
import io.breland.bbagent.server.agent.persistence.metrics.AgentToolMetricEntity;
import io.breland.bbagent.server.agent.persistence.metrics.AgentToolMetricRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PostgresAgentMetricsStore implements AgentMetricsStore {

  private final AgentMessageMetricRepository messageRepository;
  private final AgentToolMetricRepository toolRepository;

  public PostgresAgentMetricsStore(
      AgentMessageMetricRepository messageRepository, AgentToolMetricRepository toolRepository) {
    this.messageRepository = messageRepository;
    this.toolRepository = toolRepository;
  }

  @Override
  public void saveMessageMetric(AgentMessageMetric metric) {
    messageRepository.save(
        new AgentMessageMetricEntity(
            metric.id(),
            metric.occurredAt(),
            metric.transport(),
            metric.messageGuid(),
            metric.chatGuidHash(),
            metric.userKeyHash(),
            metric.modelKey(),
            metric.modelLabel(),
            metric.responsesModel(),
            metric.premium(),
            metric.workflowMode(),
            metric.createdAt()));
  }

  @Override
  public void saveToolMetric(AgentToolMetric metric) {
    toolRepository.save(
        new AgentToolMetricEntity(
            metric.id(),
            metric.occurredAt(),
            metric.transport(),
            metric.messageGuid(),
            metric.chatGuidHash(),
            metric.userKeyHash(),
            metric.toolName(),
            metric.toolCategory(),
            metric.success(),
            metric.failureType(),
            metric.durationMillis(),
            metric.modelKey(),
            metric.modelLabel(),
            metric.responsesModel(),
            metric.premium(),
            metric.workflowMode(),
            metric.createdAt()));
  }

  @Override
  public MessageMetricTotals summarizeMessages(Instant from, Instant to) {
    AgentMessageMetricRepository.TotalProjection totals = messageRepository.summarize(from, to);
    return new MessageMetricTotals(
        valueOrZero(totals == null ? null : totals.getMessageCount()),
        valueOrZero(totals == null ? null : totals.getActiveUsers()));
  }

  @Override
  public List<MessageModelMetricSummary> summarizeMessagesByModel(Instant from, Instant to) {
    return messageRepository.summarizeByModel(from, to).stream()
        .map(
            row ->
                new MessageModelMetricSummary(
                    row.getModelKey(),
                    row.getModelLabel(),
                    row.getResponsesModel(),
                    Boolean.TRUE.equals(row.getPremium()),
                    valueOrZero(row.getMessageCount()),
                    valueOrZero(row.getActiveUsers())))
        .toList();
  }

  @Override
  public List<AgentMessageMetric> findMessageMetrics(Instant from, Instant to) {
    return messageRepository
        .findAllByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(from, to)
        .stream()
        .map(PostgresAgentMetricsStore::toMessageMetric)
        .toList();
  }

  @Override
  public ToolMetricTotals summarizeTools(Instant from, Instant to) {
    AgentToolMetricRepository.TotalProjection totals = toolRepository.summarize(from, to);
    return new ToolMetricTotals(
        valueOrZero(totals == null ? null : totals.getToolCallCount()),
        valueOrZero(totals == null ? null : totals.getSuccessfulToolCalls()),
        valueOrZero(totals == null ? null : totals.getFailedToolCalls()),
        valueOrZero(totals == null ? null : totals.getActiveUsers()));
  }

  @Override
  public List<ToolMetricSummary> summarizeToolsByTool(Instant from, Instant to) {
    return toolRepository.summarizeByTool(from, to).stream()
        .map(
            row ->
                new ToolMetricSummary(
                    row.getToolName(),
                    row.getToolCategory(),
                    valueOrZero(row.getCallCount()),
                    valueOrZero(row.getSuccessfulCalls()),
                    valueOrZero(row.getFailedCalls()),
                    valueOrZero(row.getActiveUsers()),
                    row.getAverageDurationMs() == null ? 0.0 : row.getAverageDurationMs(),
                    row.getLastUsedAt()))
        .toList();
  }

  @Override
  public List<ToolAccountTypeMetricSummary> summarizeToolsByAccountType(Instant from, Instant to) {
    return toolRepository.summarizeByAccountType(from, to).stream()
        .map(
            row ->
                new ToolAccountTypeMetricSummary(
                    Boolean.TRUE.equals(row.getPremium()),
                    valueOrZero(row.getCallCount()),
                    valueOrZero(row.getSuccessfulCalls()),
                    valueOrZero(row.getFailedCalls()),
                    valueOrZero(row.getActiveUsers())))
        .toList();
  }

  private static AgentMessageMetric toMessageMetric(AgentMessageMetricEntity entity) {
    return new AgentMessageMetric(
        entity.getId(),
        entity.getOccurredAt(),
        entity.getTransport(),
        entity.getMessageGuid(),
        entity.getChatGuidHash(),
        entity.getUserKeyHash(),
        entity.getModelKey(),
        entity.getModelLabel(),
        entity.getResponsesModel(),
        entity.isPremium(),
        entity.getWorkflowMode(),
        entity.getCreatedAt());
  }

  private static long valueOrZero(Long value) {
    return value == null ? 0L : value;
  }
}
