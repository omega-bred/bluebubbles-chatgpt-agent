package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

@Slf4j
final class AgentToolActivityRunner {
  private static final int MAX_TOOL_OUTPUT_CHARS = 24_000;
  private static final int TOOL_OUTPUT_EDGE_CHARS = 12_000;

  private final BBMessageAgent messageAgent;
  private final ObjectMapper objectMapper;
  private final AgentProfileService profileService;
  private final AgentToolRegistry toolRegistry;
  private final @Nullable AgentMetricsService agentMetricsService;

  AgentToolActivityRunner(
      BBMessageAgent messageAgent,
      ObjectMapper objectMapper,
      AgentProfileService profileService,
      AgentToolRegistry toolRegistry,
      @Nullable AgentMetricsService agentMetricsService) {
    this.messageAgent = messageAgent;
    this.objectMapper = objectMapper;
    this.profileService = profileService;
    this.toolRegistry = toolRegistry;
    this.agentMetricsService = agentMetricsService;
  }

  ResponseInputItem run(
      ResponseFunctionToolCall toolCall,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    log.info("Invoking tool {}", toolCall.name());
    AgentToolRegistry.ResolvedTool resolvedTool =
        toolRegistry.resolveTool(toolCall.name(), message);
    AgentTool tool = resolvedTool.tool();
    String output;
    String failureType;
    String toolCategory = toolRegistry.toolCategory(toolCall.name());
    boolean success = false;
    Instant startedAt = Instant.now();
    try {
      JsonNode args = objectMapper.readTree(toolCall.arguments());
      args = ThreadReplySupport.applySendTextReplyDefault(toolCall.name(), args, message);
      ToolContext toolContext =
          new ToolContext(messageAgent, profileService, message, workflowContext);
      if (tool == null) {
        output = "Unknown tool: " + toolCall.name();
        failureType = "unknown_tool";
      } else {
        output =
            truncateToolOutputForModel(tool.handler().apply(toolContext, args), toolCall.name());
        failureType = classifyFailure(output);
        success = failureType == null;
      }
    } catch (Exception e) {
      output = "Tool call failed: " + e.getMessage();
      failureType = "exception";
      log.warn("Tool call failed: {}", toolCall.name(), e);
    }
    long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
    recordToolCallMetric(
        toolCall.name(), message, success, failureType, durationMillis, toolCategory);

    ResponseInputItem.FunctionCallOutput toolOutput =
        ResponseInputItem.FunctionCallOutput.builder()
            .callId(toolCall.callId())
            .output(output)
            .build();
    return ResponseInputItem.ofFunctionCallOutput(toolOutput);
  }

  static String truncateToolOutputForModel(String output, String toolName) {
    if (output == null || output.length() <= MAX_TOOL_OUTPUT_CHARS) {
      return output;
    }
    String safeToolName = StringUtils.defaultIfBlank(toolName, "tool");
    int omitted = output.length() - (TOOL_OUTPUT_EDGE_CHARS * 2);
    return output.substring(0, TOOL_OUTPUT_EDGE_CHARS)
        + "\n\n["
        + safeToolName
        + " output truncated for model context; omitted "
        + omitted
        + " characters. Re-run the tool with narrower filters, lower limits, or fewer log lines if more detail is needed.]\n\n"
        + output.substring(output.length() - TOOL_OUTPUT_EDGE_CHARS);
  }

  private void recordToolCallMetric(
      String toolName,
      IncomingMessage message,
      boolean success,
      @Nullable String failureType,
      long durationMillis,
      String toolCategory) {
    if (agentMetricsService == null) {
      return;
    }
    try {
      agentMetricsService.recordToolCall(
          new AgentToolMetricEvent(
              message, toolName, toolCategory, success, failureType, durationMillis));
    } catch (RuntimeException e) {
      log.warn("Failed to record tool metric for {}", toolName, e);
    }
  }

  private String classifyFailure(String output) {
    String normalized = StringUtils.trimToEmpty(output).toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return null;
    }
    if (normalized.startsWith("tool call failed:")) {
      return "exception";
    }
    if (normalized.startsWith("unknown tool:")) {
      return "unknown_tool";
    }
    if (normalized.startsWith("failed:")
        || normalized.startsWith("failed ")
        || normalized.startsWith("error:")
        || normalized.startsWith("unable to ")) {
      return "tool_error";
    }
    return null;
  }
}
