package io.breland.bbagent.server.agent.tools.scheduled;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import javax.annotation.Nullable;
import org.springframework.util.StringUtils;

public final class ScheduledEvents {
  private static final String WORKFLOW_ID_PREFIX = "scheduled";

  private ScheduledEvents() {}

  static @Nullable Instant scheduledInstant(
      @Nullable Long runAtEpochMillis, @Nullable String runAt, @Nullable Long delaySeconds) {
    if (runAtEpochMillis != null) {
      return Instant.ofEpochMilli(runAtEpochMillis);
    }
    if (StringUtils.hasText(runAt)) {
      try {
        return OffsetDateTime.parse(runAt).toInstant();
      } catch (DateTimeParseException ignored) {
        // Keep tool responses compact: callers turn null into "missing schedule".
      }
    }
    if (delaySeconds == null || delaySeconds < 0L) {
      return null;
    }
    return Instant.now().plusSeconds(delaySeconds);
  }

  static @Nullable Duration delayStart(@Nullable Instant scheduledAt) {
    if (scheduledAt == null) {
      return null;
    }
    Duration delay = Duration.between(Instant.now(), scheduledAt);
    return delay.isNegative() ? Duration.ZERO : delay;
  }

  static @Nullable Duration repeatInterval(@Nullable String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      Duration duration = Duration.parse(value);
      return duration.isNegative() ? Duration.ZERO : duration;
    } catch (DateTimeParseException ignored) {
      return Duration.ZERO;
    }
  }

  static @Nullable String cronSchedule(@Nullable Duration repeatInterval) {
    if (repeatInterval == null) {
      return null;
    }
    long seconds = repeatInterval.getSeconds();
    if (seconds <= 0L || seconds % 60L != 0L) {
      return null;
    }
    long minutes = seconds / 60L;
    if (minutes < 60L) {
      if (60L % minutes != 0L) {
        return null;
      }
      return "*/" + minutes + " * * * *";
    }
    if (minutes % 60L != 0L) {
      return null;
    }
    long hours = minutes / 60L;
    if (hours < 24L) {
      if (24L % hours != 0L) {
        return null;
      }
      return "0 */" + hours + " * * *";
    }
    if (hours == 24L) {
      return "0 0 * * *";
    }
    if (hours == 168L) {
      return "0 0 * * 0";
    }
    return null;
  }

  static @Nullable String chatGuid(ToolContext context, @Nullable String requestedChatGuid) {
    if (StringUtils.hasText(requestedChatGuid)) {
      return requestedChatGuid;
    }
    IncomingMessage message = context.message();
    return message == null ? null : message.chatGuid();
  }

  static String workflowIdPrefix(String chatGuid) {
    return WORKFLOW_ID_PREFIX + ":" + chatGuid + ":";
  }

  static String newWorkflowId(String chatGuid) {
    return workflowIdPrefix(chatGuid) + UUID.randomUUID();
  }

  public static boolean isScheduledWorkflowId(@Nullable String workflowId) {
    return StringUtils.hasText(workflowId) && workflowId.startsWith(WORKFLOW_ID_PREFIX + ":");
  }

  static String toJson(ToolContext context, Object value, String failureMessage) {
    try {
      return context.getMapper().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return failureMessage;
    }
  }
}
