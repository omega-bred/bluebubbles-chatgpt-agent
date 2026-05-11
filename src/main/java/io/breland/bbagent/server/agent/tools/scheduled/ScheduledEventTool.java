package io.breland.bbagent.server.agent.tools.scheduled;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.util.StringUtils;

public class ScheduledEventTool implements ToolProvider {
  public static final String TOOL_NAME = "schedule_event";
  private final CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @Schema(description = "Schedule a future task for the assistant to execute.")
  public record ScheduledEventRequest(
      @Schema(
              description = "Task to execute later (what to do and how to notify the user).",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String task,
      @Schema(
              description =
                  "ISO-8601 timestamp (with timezone) to run the task. Use this or delaySeconds.",
              example = "2024-04-05T16:00:00Z")
          String runAt,
      @Schema(description = "Epoch millis to run the task.") Long runAtEpochMillis,
      @Schema(description = "Delay in seconds before the first run.", minimum = "0")
          Long delaySeconds,
      @Schema(
              description =
                  "ISO-8601 duration between recurring runs (e.g. PT24H for daily). Omit for"
                      + " one-time tasks.")
          String repeatInterval) {}

  public ScheduledEventTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Schedule a future task for the assistant. Provide a task plus either runAt or"
            + " delaySeconds. For recurring tasks, include repeatInterval only when recurrence is"
            + " explicitly needed; repeatInterval must be representable as Unix cron, such as PT1M,"
            + " PT5M, PT1H, or PT24H. Use this for reminders, follow-ups, and watching long-running"
            + " work. After starting a long-running Coder task or workspace build, schedule a"
            + " one-time status check with delaySeconds and no repeatInterval unless the user asks"
            + " for repeated checks. For long-running work checks, write the task so that if the"
            + " work is still pending or running, the assistant must call schedule_event again for"
            + " another one-time check before ending the turn, unless the work is complete, failed,"
            + " canceled, expired, or the task's max attempts/deadline has been reached. Use the"
            + " task to describe what should happen, include any IDs needed to continue, attempt"
            + " count, deadline or callback expiration, and say how the user should be notified.",
        jsonSchema(ScheduledEventRequest.class),
        false,
        (context, args) -> {
          if (cadenceWorkflowLauncher == null) {
            return "not configured";
          }
          ScheduledEventRequest request =
              context.getMapper().convertValue(args, ScheduledEventRequest.class);
          if (!StringUtils.hasText(request.task())) {
            return "missing task";
          }
          Instant scheduledAt =
              ScheduledEvents.scheduledInstant(
                  request.runAtEpochMillis(), request.runAt(), request.delaySeconds());
          if (scheduledAt == null) {
            return "missing schedule";
          }
          Duration repeatInterval = ScheduledEvents.repeatInterval(request.repeatInterval());
          if (repeatInterval != null && repeatInterval.isZero()) {
            return "invalid repeat interval";
          }
          IncomingMessage source = context.message();
          String chatGuid = ScheduledEvents.chatGuid(context, null);
          if (source == null || !StringUtils.hasText(chatGuid)) {
            return "missing chat";
          }
          String workflowId = ScheduledEvents.newWorkflowId(chatGuid);
          String messageGuid = "scheduled-" + UUID.randomUUID();
          String text = buildScheduledText(request.task(), scheduledAt, repeatInterval);
          IncomingMessage scheduledMessage =
              new IncomingMessage(
                  chatGuid,
                  messageGuid,
                  source.threadOriginatorGuid(),
                  text,
                  false,
                  Objects.requireNonNullElse(source.service(), BBMessageAgent.IMESSAGE_SERVICE),
                  source.sender(),
                  source.isGroup(),
                  Instant.now(),
                  List.of(),
                  true);
          AgentWorkflowContext workflowContext =
              new AgentWorkflowContext(workflowId, chatGuid, messageGuid, Instant.now());
          Duration delayStart = ScheduledEvents.delayStart(scheduledAt);
          String cronSchedule = ScheduledEvents.cronSchedule(repeatInterval);
          if (repeatInterval != null && cronSchedule == null) {
            return "unsupported repeat interval: use a whole-minute interval that can be"
                + " represented as Unix cron, such as PT1M, PT5M, PT1H, or PT24H";
          }
          Map<String, Object> memo =
              Map.of(
                  "task",
                  request.task().trim(),
                  "chatGuid",
                  chatGuid,
                  "scheduledFor",
                  scheduledAt.toString(),
                  "repeatInterval",
                  repeatInterval == null ? "" : repeatInterval.toString());
          WorkflowExecution execution;
          try {
            execution =
                cadenceWorkflowLauncher.startScheduledWorkflow(
                    new CadenceMessageWorkflowRequest(
                        workflowContext, scheduledMessage, scheduledAt),
                    delayStart,
                    cronSchedule,
                    memo);
          } catch (Exception e) {
            return "failed to schedule: " + e.getMessage();
          }
          if (execution == null) {
            return "failed to schedule";
          }
          return repeatInterval != null ? "scheduled recurring task" : "scheduled";
        });
  }

  public static boolean isScheduledWorkflowId(String workflowId) {
    return ScheduledEvents.isScheduledWorkflowId(workflowId);
  }

  private static String buildScheduledText(
      String task, Instant scheduledAt, Duration repeatInterval) {
    StringBuilder builder = new StringBuilder();
    builder.append("Scheduled task trigger.");
    if (scheduledAt != null) {
      builder.append(" Scheduled for: ").append(scheduledAt).append('.');
    }
    if (repeatInterval != null) {
      builder.append(" Repeat interval: ").append(repeatInterval).append('.');
    }
    builder.append(" Task: ").append(task.trim());
    builder.append(
        " Execute now and notify the user as needed. If this is checking long-running work and the"
            + " work is still pending or running, call ");
    builder.append(TOOL_NAME);
    builder.append(
        " again before ending this turn to create another one-time check, unless the work is"
            + " complete, failed, canceled, expired, or the max attempts/deadline in the task has"
            + " been reached.");
    return builder.toString();
  }
}
