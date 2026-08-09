package io.breland.bbagent.server.agent.tools.scheduled;

import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.JsonSchemaUtilities;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ScheduledEventListTool implements ToolProvider {
  public static final String TOOL_NAME = "scheduled_event_list";
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;

  public ScheduledEventListTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher =
        Objects.requireNonNull(cadenceWorkflowLauncher, "cadenceWorkflowLauncher");
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List scheduled events for the current conversation.",
        JsonSchemaUtilities.functionParameters(
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
        false,
        (context, args) -> {
          String chatGuid = context.message() == null ? null : context.message().chatGuid();
          if (chatGuid == null || chatGuid.isBlank()) {
            return "missing chat";
          }
          String prefix = ScheduledEventTool.buildWorkflowIdPrefix(chatGuid);
          List<CadenceWorkflowLauncher.ScheduledWorkflowSummary> summaries =
              cadenceWorkflowLauncher.listScheduledWorkflows(prefix);
          return ToolJson.stringify(
              context.getMapper(),
              sanitizeSummaries(chatGuid, summaries),
              "failed to serialize scheduled events");
        });
  }

  private static List<Map<String, Object>> sanitizeSummaries(
      String chatGuid, List<CadenceWorkflowLauncher.ScheduledWorkflowSummary> summaries) {
    if (summaries == null || summaries.isEmpty()) {
      return List.of();
    }
    return summaries.stream()
        .map(summary -> sanitizeSummary(chatGuid, summary))
        .filter(Objects::nonNull)
        .toList();
  }

  private static Map<String, Object> sanitizeSummary(
      String chatGuid, CadenceWorkflowLauncher.ScheduledWorkflowSummary summary) {
    if (summary == null) {
      return null;
    }
    String scheduledEventId = ScheduledEventTool.scheduledEventId(chatGuid, summary.workflowId());
    if (scheduledEventId == null) {
      return null;
    }
    Map<String, Object> sanitized = new LinkedHashMap<>();
    sanitized.put("scheduled_event_id", scheduledEventId);
    sanitized.put("run_id", summary.runId());
    sanitized.put("workflow_type", summary.workflowType());
    sanitized.put("status", summary.status());
    sanitized.put("start_time_millis", summary.startTimeMillis());
    sanitized.put("execution_time_millis", summary.executionTimeMillis());
    sanitized.put("memo", sanitizeMemo(summary.memo()));
    return sanitized;
  }

  private static Map<String, Object> sanitizeMemo(Map<String, Object> memo) {
    if (memo == null || memo.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> sanitized = new LinkedHashMap<>(memo);
    sanitized.remove("chatGuid");
    return sanitized;
  }
}
