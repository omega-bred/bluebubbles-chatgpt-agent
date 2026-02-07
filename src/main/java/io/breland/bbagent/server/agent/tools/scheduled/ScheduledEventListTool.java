package io.breland.bbagent.server.agent.tools.scheduled;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class ScheduledEventListTool implements ToolProvider {
  public static final String TOOL_NAME = "scheduled_event_list";
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @Schema(description = "List scheduled tasks for a chat.")
  public record ScheduledEventListRequest(
      @Schema(
              description =
                  "Chat GUID to list scheduled events for. Defaults to the current conversation.")
          String chatGuid) {}

  public ScheduledEventListTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "List scheduled events for the current conversation.",
        jsonSchema(ScheduledEventListRequest.class),
        false,
        (context, args) -> {
          if (cadenceWorkflowLauncher == null) {
            return "not configured";
          }
          ScheduledEventListRequest request =
              context.getMapper().convertValue(args, ScheduledEventListRequest.class);
          String chatGuid = request.chatGuid();
          if (chatGuid == null || chatGuid.isBlank()) {
            if (context.message() != null) {
              chatGuid = context.message().chatGuid();
            }
          }
          if (chatGuid == null || chatGuid.isBlank()) {
            return "missing chat";
          }
          String prefix = ScheduledEventTool.buildWorkflowIdPrefix(chatGuid);
          List<CadenceWorkflowLauncher.ScheduledWorkflowSummary> summaries =
              cadenceWorkflowLauncher.listScheduledWorkflows(prefix);
          try {
            return context.getMapper().writeValueAsString(summaries);
          } catch (Exception e) {
            return "failed to serialize scheduled events";
          }
        });
  }
}
