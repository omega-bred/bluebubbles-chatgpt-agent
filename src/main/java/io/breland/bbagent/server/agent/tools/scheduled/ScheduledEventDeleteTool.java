package io.breland.bbagent.server.agent.tools.scheduled;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

public class ScheduledEventDeleteTool implements ToolProvider {
  public static final String TOOL_NAME = "scheduled_event_delete";
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @Schema(description = "Delete a scheduled task.")
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScheduledEventDeleteRequest(
      @Schema(
              description =
                  "Sanitized scheduled event ID from scheduled_event_list for the current conversation.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          @JsonProperty("scheduled_event_id")
          String scheduledEventId,
      @Schema(description = "Optional run ID for the scheduled task.") @JsonProperty("run_id")
          String runId) {}

  public ScheduledEventDeleteTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher =
        Objects.requireNonNull(cadenceWorkflowLauncher, "cadenceWorkflowLauncher");
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a scheduled event for the current conversation by scheduled_event_id.",
        jsonSchema(ScheduledEventDeleteRequest.class),
        false,
        (context, args) -> {
          ScheduledEventDeleteRequest request =
              context.getMapper().convertValue(args, ScheduledEventDeleteRequest.class);
          String chatGuid = context.message() == null ? null : context.message().chatGuid();
          if (chatGuid == null || chatGuid.isBlank()) {
            return "missing chat";
          }
          String scheduledEventId = request.scheduledEventId();
          if (scheduledEventId == null || scheduledEventId.isBlank()) {
            return "missing scheduled_event_id";
          }
          if (scheduledEventId.contains(":")) {
            return "invalid scheduled_event_id";
          }
          String workflowId = ScheduledEventTool.buildWorkflowId(chatGuid, scheduledEventId);
          boolean deleted =
              cadenceWorkflowLauncher.terminateWorkflow(
                  workflowId, request.runId(), "deleted via scheduled event tool");
          return deleted ? "deleted" : "not found";
        });
  }
}
