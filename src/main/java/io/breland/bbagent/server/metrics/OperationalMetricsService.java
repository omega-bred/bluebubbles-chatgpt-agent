package io.breland.bbagent.server.metrics;

import com.openai.models.ResponsesModel;
import com.openai.models.responses.ResponseCreateParams;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class OperationalMetricsService {
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE = "failure";
  private static final String FAILURE_NONE = "none";
  private static final int MAX_TAG_VALUE_LENGTH = 120;

  private final @Nullable MeterRegistry meterRegistry;
  private final AtomicInteger blueBubblesHealthUp = new AtomicInteger(0);
  private final AtomicInteger blueBubblesIcloudConnected = new AtomicInteger(0);
  private final AtomicLong blueBubblesLastCheckEpochSeconds = new AtomicLong(0L);
  private final AtomicLong blueBubblesLastSuccessEpochSeconds = new AtomicLong(0L);
  private final AtomicLong blueBubblesConsecutiveFailures = new AtomicLong(0L);
  private final AtomicLong memoryOldestExtractionAgeSeconds = new AtomicLong(0L);
  private final AtomicLong memoryOldestProjectionAgeSeconds = new AtomicLong(0L);
  private final AtomicLong memoryFailedWorkCount = new AtomicLong(0L);

  public OperationalMetricsService(@Nullable MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    registerGauge(
        "bbagent.bluebubbles.health.up",
        "Whether the last BlueBubbles health check was healthy",
        blueBubblesHealthUp);
    registerGauge(
        "bbagent.bluebubbles.health.icloud.connected",
        "Whether the last BlueBubbles iCloud account check was connected",
        blueBubblesIcloudConnected);
    registerGauge(
        "bbagent.bluebubbles.health.last_check.epoch_seconds",
        "Epoch seconds for the last BlueBubbles health check",
        blueBubblesLastCheckEpochSeconds);
    registerGauge(
        "bbagent.bluebubbles.health.last_success.epoch_seconds",
        "Epoch seconds for the last healthy BlueBubbles health check",
        blueBubblesLastSuccessEpochSeconds);
    registerGauge(
        "bbagent.bluebubbles.health.consecutive_failures",
        "Consecutive failed BlueBubbles health checks",
        blueBubblesConsecutiveFailures);
    registerGauge(
        "bbagent.memory.backlog.extraction.age.seconds",
        "Age in seconds of the oldest due conversation memory extraction",
        memoryOldestExtractionAgeSeconds);
    registerGauge(
        "bbagent.memory.backlog.projection.age.seconds",
        "Age in seconds of the oldest due conversation memory projection",
        memoryOldestProjectionAgeSeconds);
    registerGauge(
        "bbagent.memory.backlog.failed.work",
        "Conversation memory work items with a recorded failure",
        memoryFailedWorkCount);
  }

  public void recordAcceptedMessage(
      String transport, String modelKey, boolean premium, String workflowMode) {
    incrementCounter(
        "bbagent.agent.message.accepted.count",
        "Accepted inbound agent messages",
        Tags.of(
            "transport",
            tagValue(transport, "unknown"),
            "model_key",
            tagValue(modelKey, "unknown"),
            "account_type",
            premium ? "premium" : "standard",
            "workflow_mode",
            tagValue(workflowMode, "unknown")));
  }

  public void recordAgentToolInvocation(
      String transport,
      String toolName,
      String toolCategory,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    Tags tags =
        Tags.of(
            "transport",
            tagValue(transport, "unknown"),
            "tool_name",
            tagValue(toolName, "unknown"),
            "tool_category",
            tagValue(toolCategory, "other"),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.agent.tool.invocation", "Agent tool invocation", duration, tags);
  }

  public void recordLlmCall(
      String transport,
      String operation,
      @Nullable ResponseCreateParams request,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    recordLlmCall(transport, operation, responseModelName(request), success, failureType, duration);
  }

  public void recordLlmCall(
      String transport,
      String operation,
      @Nullable String model,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    recordLlmCall(transport, operation, "unknown", model, success, failureType, duration);
  }

  public void recordLlmCall(
      String transport,
      String operation,
      @Nullable String provider,
      @Nullable String model,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    Tags tags =
        Tags.of(
            "transport",
            tagValue(transport, "unknown"),
            "operation",
            tagValue(operation, "unknown"),
            "provider",
            tagValue(provider, "unknown"),
            "model",
            modelTagValue(model),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount("bbagent.agent.llm.call", "Agent LLM call", duration, tags);
  }

  public void recordDecisionsRequest(
      String operation, String model, String outcome, Duration duration) {
    Tags tags = Tags.of("operation", operation, "model", modelTagValue(model), "outcome", outcome);
    incrementCounter("bbagent.decisions.request.count", "Decisions API requests", tags);
    recordTimer(
        "bbagent.decisions.request.duration", "Decisions API request duration", duration, tags);
  }

  public void recordDecisionOutcome(String operation, String outcome) {
    incrementCounter(
        "bbagent.decisions.action.count",
        "Decisions application outcomes",
        Tags.of("operation", operation, "outcome", outcome));
  }

  public void recordBlueBubblesOperation(
      String operation, boolean success, @Nullable String failureType, Duration duration) {
    Tags tags =
        Tags.of(
            "operation",
            tagValue(operation, "unknown"),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.bluebubbles.operation", "BlueBubbles API operation", duration, tags);
  }

  public void recordBlueBubblesHealthCheck(
      boolean healthy, boolean iCloudConnected, @Nullable String failureType, Duration duration) {
    long nowEpochSeconds = Instant.now().getEpochSecond();
    blueBubblesHealthUp.set(healthy ? 1 : 0);
    blueBubblesIcloudConnected.set(iCloudConnected ? 1 : 0);
    blueBubblesLastCheckEpochSeconds.set(nowEpochSeconds);
    if (healthy) {
      blueBubblesLastSuccessEpochSeconds.set(nowEpochSeconds);
      blueBubblesConsecutiveFailures.set(0L);
    } else {
      blueBubblesConsecutiveFailures.incrementAndGet();
    }

    Tags tags =
        Tags.of(
            "outcome",
            outcome(healthy),
            "icloud_connected",
            Boolean.toString(iCloudConnected),
            "failure_type",
            failureTag(healthy, failureType));
    recordDurationAndCount(
        "bbagent.bluebubbles.health.check", "BlueBubbles scheduled health check", duration, tags);
  }

  public static String failureType(Throwable throwable) {
    if (throwable == null) {
      return "exception";
    }
    if (ExceptionUtils.indexOfType(throwable, TimeoutException.class) >= 0) {
      return "timeout";
    }
    if (ExceptionUtils.indexOfType(throwable, InterruptedException.class) >= 0) {
      return "interrupted";
    }
    if (ExceptionUtils.indexOfType(throwable, IllegalArgumentException.class) >= 0) {
      return "invalid_request";
    }
    if (ExceptionUtils.indexOfType(throwable, IllegalStateException.class) >= 0) {
      return "invalid_response";
    }
    if (ExceptionUtils.indexOfType(throwable, IOException.class) >= 0) {
      return "io_exception";
    }
    return "exception";
  }

  public void recordMemoryExtraction(
      boolean success, @Nullable String failureType, Duration duration) {
    Tags tags =
        Tags.of("outcome", outcome(success), "failure_type", failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.memory.extraction", "Conversation memory extraction", duration, tags);
  }

  public void recordMemoryExtractionCandidate(String kind, String status, boolean accepted) {
    Tags tags =
        Tags.of(
            "kind",
            memoryCandidateKindTag(kind),
            "status",
            memoryCandidateStatusTag(status),
            "accepted",
            Boolean.toString(accepted));
    incrementCounter(
        "bbagent.memory.extraction.candidate.count",
        "Conversation memory extraction candidate count",
        tags);
  }

  public void recordMemoryWorkLag(Duration lag) {
    recordTimer("bbagent.memory.work.lag", "Conversation memory work lag", lag, Tags.empty());
  }

  public void recordMemoryProjection(
      String operation, boolean success, @Nullable String failureType, Duration duration) {
    recordMemoryOperation(
        "projection", "Conversation memory projection", operation, success, failureType, duration);
  }

  public void recordMemoryDigest(
      String operation, boolean success, @Nullable String failureType, Duration duration) {
    recordMemoryOperation(
        "digest", "Conversation memory digest", operation, success, failureType, duration);
  }

  public void recordMemoryCatchup(
      boolean success, @Nullable String failureType, Duration duration) {
    Tags tags =
        Tags.of("outcome", outcome(success), "failure_type", failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.memory.catchup", "Conversation memory catch-up", duration, tags);
  }

  public void recordMemoryQuestionAnswer(
      String action,
      @Nullable String model,
      long messageCount,
      long pageCount,
      long windowCount,
      long modelCallCount,
      long reductionCount,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    Tags tags =
        Tags.of(
            "action",
            tagValue(action, "unknown"),
            "model",
            modelTagValue(model),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.memory.question.answer", "Group question answer", duration, tags);
    incrementCounter(
        "bbagent.memory.question.answer.message.count",
        "Group question messages",
        tags,
        Math.max(0L, messageCount));
    incrementCounter(
        "bbagent.memory.question.answer.page.count",
        "Group question source pages",
        tags,
        Math.max(0L, pageCount));
    incrementCounter(
        "bbagent.memory.question.answer.window.count",
        "Group question windows",
        tags,
        Math.max(0L, windowCount));
    incrementCounter(
        "bbagent.memory.question.answer.model.call.count",
        "Group question model calls",
        tags,
        Math.max(0L, modelCallCount));
    incrementCounter(
        "bbagent.memory.question.answer.reduction.count",
        "Group question reductions",
        tags,
        Math.max(0L, reductionCount));
  }

  public void recordMemoryProactiveDelivery(
      String deliveryMode, boolean success, @Nullable String failureType, Duration duration) {
    Tags tags =
        Tags.of(
            "delivery_mode",
            tagValue(deliveryMode, "unknown"),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount(
        "bbagent.memory.proactive.delivery",
        "Proactive conversation memory delivery",
        duration,
        tags);
  }

  public void recordMemoryCleanup(
      String operation,
      long itemCount,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    recordMemoryOperation(
        "cleanup", "Conversation memory cleanup", operation, success, failureType, duration);
    incrementCounter(
        "bbagent.memory.cleanup.item.count",
        "Conversation memory cleanup item count",
        Tags.of("operation", tagValue(operation, "unknown")),
        Math.max(0L, itemCount));
  }

  public void updateMemoryBacklog(
      Duration oldestExtractionAge, Duration oldestProjectionAge, long failedWorkCount) {
    memoryOldestExtractionAgeSeconds.set(normalizedDuration(oldestExtractionAge).toSeconds());
    memoryOldestProjectionAgeSeconds.set(normalizedDuration(oldestProjectionAge).toSeconds());
    memoryFailedWorkCount.set(Math.max(0L, failedWorkCount));
  }

  private void recordMemoryOperation(
      String metricComponent,
      String description,
      String operation,
      boolean success,
      @Nullable String failureType,
      Duration duration) {
    Tags tags =
        Tags.of(
            "operation",
            tagValue(operation, "unknown"),
            "outcome",
            outcome(success),
            "failure_type",
            failureTag(success, failureType));
    recordDurationAndCount("bbagent.memory." + metricComponent, description, duration, tags);
  }

  private void recordDurationAndCount(
      String name, String description, Duration duration, Tags tags) {
    recordTimer(name + ".duration", description + " duration", duration, tags);
    incrementCounter(name + ".count", description + " count", tags);
  }

  private void registerGauge(String name, String description, Number value) {
    if (meterRegistry == null) {
      return;
    }
    Gauge.builder(name, value, Number::doubleValue)
        .description(description)
        .register(meterRegistry);
  }

  private void recordTimer(String name, String description, Duration duration, Tags tags) {
    if (meterRegistry == null) {
      return;
    }
    Timer.builder(name)
        .description(description)
        .tags(tags)
        .register(meterRegistry)
        .record(normalizedDuration(duration));
  }

  private void incrementCounter(String name, String description, Tags tags) {
    incrementCounter(name, description, tags, 1.0);
  }

  private void incrementCounter(String name, String description, Tags tags, double amount) {
    if (meterRegistry == null) {
      return;
    }
    Counter.builder(name)
        .description(description)
        .tags(tags)
        .register(meterRegistry)
        .increment(amount);
  }

  private static Duration normalizedDuration(Duration duration) {
    if (duration == null || duration.isNegative()) {
      return Duration.ZERO;
    }
    return duration;
  }

  private static String responseModelName(@Nullable ResponseCreateParams request) {
    if (request == null) {
      return "unknown";
    }
    return request.model().map(OperationalMetricsService::responseModelName).orElse("unknown");
  }

  private static String responseModelName(ResponsesModel model) {
    if (model == null) {
      return "unknown";
    }
    if (model.isString()) {
      return model.asString();
    }
    if (model.isChat()) {
      return model.asChat().asString();
    }
    if (model.isOnly()) {
      return model.asOnly().asString();
    }
    return "unknown";
  }

  private static String outcome(boolean success) {
    return success ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
  }

  private static String failureTag(boolean success, @Nullable String failureType) {
    return success ? FAILURE_NONE : tagValue(failureType, "unknown");
  }

  private static String tagValue(@Nullable String value, String fallback) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return fallback;
    }
    return StringUtils.truncate(trimmed.toLowerCase(Locale.ROOT), MAX_TAG_VALUE_LENGTH);
  }

  private static String modelTagValue(@Nullable String value) {
    String trimmed = StringUtils.trimToNull(value);
    return trimmed == null ? "unknown" : StringUtils.truncate(trimmed, MAX_TAG_VALUE_LENGTH);
  }

  private static String memoryCandidateKindTag(@Nullable String value) {
    String normalized = tagValue(value, "unknown");
    return normalized.equals("group_decision") || normalized.equals("group_fact")
        ? normalized
        : "unknown";
  }

  private static String memoryCandidateStatusTag(@Nullable String value) {
    String normalized = tagValue(value, "unknown");
    return normalized.equals("provisional")
            || normalized.equals("confirmed")
            || normalized.equals("superseded")
            || normalized.equals("deleted")
        ? normalized
        : "unknown";
  }
}
