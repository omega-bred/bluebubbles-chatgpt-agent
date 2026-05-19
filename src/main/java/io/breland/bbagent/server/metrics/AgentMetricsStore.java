package io.breland.bbagent.server.metrics;

import java.time.Instant;
import java.util.List;

public interface AgentMetricsStore {

  void saveMessageMetric(AgentMessageMetric metric);

  void saveToolMetric(AgentToolMetric metric);

  MessageMetricTotals summarizeMessages(Instant from, Instant to);

  List<MessageModelMetricSummary> summarizeMessagesByModel(Instant from, Instant to);

  List<AgentMessageMetric> findMessageMetrics(Instant from, Instant to);

  ToolMetricTotals summarizeTools(Instant from, Instant to);

  List<ToolMetricSummary> summarizeToolsByTool(Instant from, Instant to);

  List<ToolAccountTypeMetricSummary> summarizeToolsByAccountType(Instant from, Instant to);
}
