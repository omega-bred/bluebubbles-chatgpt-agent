package io.breland.bbagent.server.agent.tools.scheduled;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public class ScheduledEventDeleteTool implements ToolProvider {
  public static final String TOOL_NAME = "scheduled_event_delete";
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @Schema(description = "Delete a scheduled task.")
  public record ScheduledEventDeleteRequest(
      @Schema(
              description = "Workflow ID of the scheduled task.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String workflowId,
      @Schema(description = "Optional run ID for the scheduled task.") String runId) {}

  public ScheduledEventDeleteTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Delete a scheduled event by workflow ID.",
        jsonSchema(ScheduledEventDeleteRequest.class),
        false,
        (context, args) -> {
          if (cadenceWorkflowLauncher == null) {
            return "not configured";
          }
          ScheduledEventDeleteRequest request =
              context.getMapper().convertValue(args, ScheduledEventDeleteRequest.class);
          String workflowId = request.workflowId();
          if (workflowId == null || workflowId.isBlank()) {
            return "missing workflowId";
          }
          if (!ScheduledEventTool.isScheduledWorkflowId(workflowId)) {
            return "invalid workflowId";
          }
          boolean deleted =
              cadenceWorkflowLauncher.terminateWorkflow(
                  workflowId, request.runId(), "deleted via scheduled event tool");
          return deleted ? "deleted" : "not found";
        });
  }
}
