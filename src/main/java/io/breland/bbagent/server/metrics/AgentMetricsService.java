package io.breland.bbagent.server.metrics;

import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.Instant;

public interface AgentMetricsService {

  void recordAcceptedMessage(IncomingMessage message, AgentWorkflowProperties.Mode workflowMode);

  void recordToolCall(AgentToolMetricEvent event);

  AdminStatsResponse getStatistics(Instant from, Instant to);
}
