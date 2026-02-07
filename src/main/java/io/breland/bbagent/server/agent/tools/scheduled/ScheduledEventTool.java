package io.breland.bbagent.server.agent.tools.scheduled;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

public class ScheduledEventTool implements ToolProvider {
  public static final String TOOL_NAME = "schedule_event";
  static final String WORKFLOW_ID_PREFIX = "scheduled";
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
                  "ISO-8601 duration between recurring runs (e.g. PT24H for daily). Omit for one-time tasks.")
          String repeatInterval) {}

  public ScheduledEventTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Schedule a future task for the assistant. Provide a task plus either runAt or delaySeconds. "
            + "For recurring tasks, include repeatInterval (ISO-8601 duration, e.g. PT24H). "
            + "Use the task to describe what should happen and how the user should be notified.",
        jsonSchema(ScheduledEventRequest.class),
        false,
        (context, args) -> {
          if (cadenceWorkflowLauncher == null) {
            return "not configured";
          }
          ScheduledEventRequest request =
              context.getMapper().convertValue(args, ScheduledEventRequest.class);
          if (request.task() == null || request.task().isBlank()) {
            return "missing task";
          }
          Instant scheduledAt = resolveScheduledInstant(request);
          if (scheduledAt == null) {
            return "missing schedule";
          }
          Duration repeatInterval = resolveRepeatInterval(request);
          if (repeatInterval != null && repeatInterval.isZero()) {
            return "invalid repeat interval";
          }
          IncomingMessage source = context.message();
          if (source == null || source.chatGuid() == null || source.chatGuid().isBlank()) {
            return "missing chat";
          }
          String workflowId = buildWorkflowId(source.chatGuid());
          String messageGuid = "scheduled-" + UUID.randomUUID();
          String text = buildScheduledText(request.task(), scheduledAt, repeatInterval);
          IncomingMessage scheduledMessage =
              new IncomingMessage(
                  source.chatGuid(),
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
              new AgentWorkflowContext(workflowId, source.chatGuid(), messageGuid, Instant.now());
          Duration delayStart = resolveDelayStart(scheduledAt);
          String cronSchedule = resolveCronSchedule(repeatInterval);
          Map<String, Object> memo =
              Map.of(
                  "task",
                  request.task().trim(),
                  "chatGuid",
                  source.chatGuid(),
                  "scheduledFor",
                  scheduledAt.toString(),
                  "repeatInterval",
                  repeatInterval == null ? "" : repeatInterval.toString());
          cadenceWorkflowLauncher.startScheduledWorkflow(
              new CadenceMessageWorkflowRequest(workflowContext, scheduledMessage, scheduledAt),
              delayStart,
              cronSchedule,
              memo);
          return repeatInterval != null ? "scheduled recurring task" : "scheduled";
        });
  }

  private static Instant resolveScheduledInstant(ScheduledEventRequest request) {
    if (request.runAtEpochMillis() != null) {
      return Instant.ofEpochMilli(request.runAtEpochMillis());
    }
    if (request.runAt() != null && !request.runAt().isBlank()) {
      try {
        return OffsetDateTime.parse(request.runAt()).toInstant();
      } catch (Exception ignored) {
        // fall through
      }
    }
    if (request.delaySeconds() != null) {
      if (request.delaySeconds() < 0L) {
        return null;
      }
      return Instant.now().plusSeconds(request.delaySeconds());
    }
    return null;
  }

  private static Duration resolveDelayStart(Instant scheduledAt) {
    if (scheduledAt == null) {
      return null;
    }
    Duration delay = Duration.between(Instant.now(), scheduledAt);
    if (delay.isNegative()) {
      return Duration.ZERO;
    }
    return delay;
  }

  private static Duration resolveRepeatInterval(ScheduledEventRequest request) {
    if (request.repeatInterval() == null || request.repeatInterval().isBlank()) {
      return null;
    }
    try {
      Duration duration = Duration.parse(request.repeatInterval());
      if (duration.isNegative()) {
        return Duration.ZERO;
      }
      return duration;
    } catch (Exception ignored) {
      return Duration.ZERO;
    }
  }

  private static String resolveCronSchedule(@Nullable Duration repeatInterval) {
    if (repeatInterval == null) {
      return null;
    }
    long seconds = repeatInterval.getSeconds();
    if (seconds <= 0L) {
      return null;
    }
    return "@every " + seconds + "s";
  }

  static String buildWorkflowIdPrefix(String chatGuid) {
    return WORKFLOW_ID_PREFIX + ":" + chatGuid + ":";
  }

  static boolean isScheduledWorkflowId(String workflowId) {
    return workflowId != null && workflowId.startsWith(WORKFLOW_ID_PREFIX + ":");
  }

  private static String buildWorkflowId(String chatGuid) {
    return buildWorkflowIdPrefix(chatGuid) + UUID.randomUUID();
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
    builder.append(" Execute now and notify the user as needed.");
    return builder.toString();
  }
}
