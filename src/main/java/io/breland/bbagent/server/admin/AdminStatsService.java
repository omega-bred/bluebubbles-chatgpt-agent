package io.breland.bbagent.server.admin;

import static io.breland.bbagent.server.TimeSupport.offset;

import io.breland.bbagent.generated.model.AdminBucketModelStats;
import io.breland.bbagent.generated.model.AdminModelStats;
import io.breland.bbagent.generated.model.AdminSenderStats;
import io.breland.bbagent.generated.model.AdminStatsBucket;
import io.breland.bbagent.generated.model.AdminStatsPeriod;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.generated.model.AdminToolAccountTypeStats;
import io.breland.bbagent.generated.model.AdminToolStats;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.canary.AgentCanaryService;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import io.breland.bbagent.server.metrics.AgentMessageMetric;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.AgentMetricsStore;
import io.breland.bbagent.server.metrics.AgentToolMetric;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
import io.breland.bbagent.server.metrics.MessageMetricTotals;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.breland.bbagent.server.metrics.ToolMetricTotals;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStatsService implements AgentMetricsService {
  private static final String WORKFLOW_MODE = "CADENCE";
  private static final int TOP_SENDER_LIMIT = 10;
  private static final int ACCOUNT_BUCKET_PREFIX_LENGTH = 12;

  private final AgentMetricsStore metricsStore;
  private final ModelAccessService modelAccessService;
  private final AgentAccountResolver accountResolver;
  private final UmamiAnalyticsService umamiAnalyticsService;
  private final OperationalMetricsService operationalMetricsService;
  private final AgentCanaryService canaryService;

  public AdminStatsService(
      AgentMetricsStore metricsStore,
      ModelAccessService modelAccessService,
      @Nullable AgentAccountResolver accountResolver,
      @Nullable UmamiAnalyticsService umamiAnalyticsService,
      @Nullable OperationalMetricsService operationalMetricsService,
      @Nullable AgentCanaryService canaryService) {
    this.metricsStore = metricsStore;
    this.modelAccessService = modelAccessService;
    this.accountResolver = accountResolver;
    this.umamiAnalyticsService = umamiAnalyticsService;
    this.operationalMetricsService = operationalMetricsService;
    this.canaryService = canaryService;
  }

  @Transactional
  @Override
  public void recordAcceptedMessage(IncomingMessage message) {
    if (message == null) {
      return;
    }
    if (isCanaryAccount(message)) {
      return;
    }
    MetricContext context = metricContext(message);
    Instant now = Instant.now();
    metricsStore.saveMessageMetric(
        new AgentMessageMetric(
            UUID.randomUUID().toString(),
            now,
            firstNonBlank(message.transportOrDefault(), "unknown"),
            StringUtils.trimToNull(message.messageGuid()),
            hashNullable(message.chatGuid()),
            context.userKeyHash(),
            firstNonBlank(context.modelAccess().currentModelKey(), "unknown"),
            firstNonBlank(
                context.modelAccess().currentModelLabel(),
                context.modelAccess().currentModelKey(),
                "Unknown"),
            firstNonBlank(context.modelAccess().responsesModel(), "unknown"),
            context.modelAccess().premium(),
            WORKFLOW_MODE,
            now));
    if (operationalMetricsService != null) {
      operationalMetricsService.recordAcceptedMessage(
          firstNonBlank(message.metricTransport(), "unknown"),
          firstNonBlank(context.modelAccess().currentModelKey(), "unknown"),
          context.modelAccess().premium(),
          WORKFLOW_MODE);
    }
    recordAcceptedMessageAnalytics(message, context);
  }

  @Transactional
  @Override
  public void recordToolCall(AgentToolMetricEvent event) {
    if (event == null || event.message() == null) {
      return;
    }
    IncomingMessage message = event.message();
    if (isCanaryAccount(message)) {
      return;
    }
    MetricContext context = metricContext(message);
    Instant now = Instant.now();
    boolean premium = context.modelAccess().premium();
    metricsStore.saveToolMetric(
        new AgentToolMetric(
            UUID.randomUUID().toString(),
            now,
            firstNonBlank(message.transportOrDefault(), "unknown"),
            StringUtils.trimToNull(message.messageGuid()),
            hashNullable(message.chatGuid()),
            context.userKeyHash(),
            firstNonBlank(event.toolName(), "unknown"),
            firstNonBlank(event.toolCategory(), "other"),
            event.success(),
            StringUtils.trimToNull(event.failureType()),
            Math.max(0L, event.durationMillis()),
            firstNonBlank(context.modelAccess().currentModelKey(), "unknown"),
            firstNonBlank(
                context.modelAccess().currentModelLabel(),
                context.modelAccess().currentModelKey(),
                "Unknown"),
            firstNonBlank(context.modelAccess().responsesModel(), "unknown"),
            premium,
            WORKFLOW_MODE,
            now));
    if (operationalMetricsService != null) {
      operationalMetricsService.recordAgentToolInvocation(
          firstNonBlank(message.metricTransport(), "unknown"),
          firstNonBlank(event.toolName(), "unknown"),
          firstNonBlank(event.toolCategory(), "other"),
          event.success(),
          StringUtils.trimToNull(event.failureType()),
          Duration.ofMillis(Math.max(0L, event.durationMillis())));
    }
    recordToolCallAnalytics(event, context);
  }

  @Transactional(readOnly = true)
  @Override
  public AdminStatsResponse getStatistics(Instant from, Instant to) {
    MessageMetricTotals totals = metricsStore.summarizeMessages(from, to);
    long messageCount = totals.messageCount();
    long activeUsers = totals.activeUsers();
    ToolMetricTotals toolTotals = metricsStore.summarizeTools(from, to);
    long toolCallCount = toolTotals.toolCallCount();
    BucketSize bucketSize = chooseBucketSize(from, to);
    List<AgentMessageMetric> events = metricsStore.findMessageMetrics(from, to);

    return new AdminStatsResponse()
        .period(
            new AdminStatsPeriod()
                .from(offset(from))
                .to(offset(to))
                .bucketSize(AdminStatsPeriod.BucketSizeEnum.fromValue(bucketSize.value)))
        .totalMessages(messageCount)
        .activeUsers(activeUsers)
        .averageMessagesPerUser(activeUsers == 0 ? 0.0 : (double) messageCount / activeUsers)
        .totalToolCalls(toolCallCount)
        .successfulToolCalls(toolTotals.successfulToolCalls())
        .failedToolCalls(toolTotals.failedToolCalls())
        .toolSuccessRate(successRate(toolTotals.successfulToolCalls(), toolCallCount))
        .models(modelStats(from, to, messageCount))
        .senders(senderStats(events, messageCount))
        .timeline(bucketStats(from, to, bucketSize, events))
        .tools(toolStats(from, to, toolCallCount))
        .toolAccountTypes(toolAccountTypeStats(from, to, toolCallCount));
  }

  private void recordAcceptedMessageAnalytics(IncomingMessage message, MetricContext context) {
    if (umamiAnalyticsService == null) {
      return;
    }
    Map<String, Object> data = new HashMap<>();
    data.put("transport", firstNonBlank(message.transportOrDefault(), "unknown"));
    data.put("is_group", message.isGroup());
    data.put("workflow_mode", WORKFLOW_MODE);
    data.put("model_key", firstNonBlank(context.modelAccess().currentModelKey(), "unknown"));
    data.put(
        "model_label",
        firstNonBlank(
            context.modelAccess().currentModelLabel(),
            context.modelAccess().currentModelKey(),
            "Unknown"));
    data.put("responses_model", firstNonBlank(context.modelAccess().responsesModel(), "unknown"));
    data.put("is_premium", context.modelAccess().premium());
    umamiAnalyticsService.track(
        "agent_message_accepted", "/server/agent/message", context.userKeyHash(), data);
  }

  private void recordToolCallAnalytics(AgentToolMetricEvent event, MetricContext context) {
    if (umamiAnalyticsService == null || event.message() == null) {
      return;
    }
    IncomingMessage message = event.message();
    Map<String, Object> data = new HashMap<>();
    data.put("transport", firstNonBlank(message.transportOrDefault(), "unknown"));
    data.put("workflow_mode", WORKFLOW_MODE);
    data.put("tool_name", firstNonBlank(event.toolName(), "unknown"));
    data.put("tool_category", firstNonBlank(event.toolCategory(), "other"));
    data.put("success", event.success());
    data.put("failure_type", StringUtils.trimToNull(event.failureType()));
    data.put("duration_ms", Math.max(0L, event.durationMillis()));
    data.put("model_key", firstNonBlank(context.modelAccess().currentModelKey(), "unknown"));
    data.put("is_premium", context.modelAccess().premium());
    umamiAnalyticsService.track(
        "agent_tool_call", "/server/agent/tool", context.userKeyHash(), data);
  }

  private List<AdminModelStats> modelStats(Instant from, Instant to, long totalMessages) {
    return metricsStore.summarizeMessagesByModel(from, to).stream()
        .map(
            row ->
                new AdminModelStats()
                    .modelKey(firstNonBlank(row.modelKey(), "unknown"))
                    .modelLabel(firstNonBlank(row.modelLabel(), row.modelKey(), "Unknown"))
                    .responsesModel(firstNonBlank(row.responsesModel(), "unknown"))
                    .isPremium(row.premium())
                    .messageCount(row.messageCount())
                    .activeUsers(row.activeUsers())
                    .percentage(
                        totalMessages == 0 ? 0.0 : (double) row.messageCount() / totalMessages))
        .toList();
  }

  private List<AdminToolStats> toolStats(Instant from, Instant to, long totalToolCalls) {
    return metricsStore.summarizeToolsByTool(from, to).stream()
        .map(
            row ->
                new AdminToolStats()
                    .toolName(firstNonBlank(row.toolName(), "unknown"))
                    .toolCategory(firstNonBlank(row.toolCategory(), "other"))
                    .callCount(row.callCount())
                    .successfulCalls(row.successfulCalls())
                    .failedCalls(row.failedCalls())
                    .activeUsers(row.activeUsers())
                    .successRate(successRate(row.successfulCalls(), row.callCount()))
                    .averageDurationMs(row.averageDurationMs())
                    .lastUsedAt(offset(row.lastUsedAt()))
                    .percentage(
                        totalToolCalls == 0 ? 0.0 : (double) row.callCount() / totalToolCalls))
        .toList();
  }

  private List<AdminToolAccountTypeStats> toolAccountTypeStats(
      Instant from, Instant to, long totalToolCalls) {
    return metricsStore.summarizeToolsByAccountType(from, to).stream()
        .map(
            row ->
                new AdminToolAccountTypeStats()
                    .isPremium(row.premium())
                    .callCount(row.callCount())
                    .successfulCalls(row.successfulCalls())
                    .failedCalls(row.failedCalls())
                    .activeUsers(row.activeUsers())
                    .successRate(successRate(row.successfulCalls(), row.callCount()))
                    .percentage(
                        totalToolCalls == 0 ? 0.0 : (double) row.callCount() / totalToolCalls))
        .toList();
  }

  private List<AdminSenderStats> senderStats(List<AgentMessageMetric> events, long totalMessages) {
    Map<String, MutableSenderStats> senders = new HashMap<>();
    for (AgentMessageMetric event : events) {
      String accountKeyHash = firstNonBlank(event.userKeyHash(), "unknown");
      MutableSenderStats sender = senders.computeIfAbsent(accountKeyHash, MutableSenderStats::new);
      sender.record(event);
    }

    return senders.values().stream()
        .sorted(
            Comparator.comparingLong(MutableSenderStats::messageCount)
                .reversed()
                .thenComparing(MutableSenderStats::accountKeyHash))
        .limit(TOP_SENDER_LIMIT)
        .map(sender -> sender.toResponse(totalMessages))
        .toList();
  }

  private List<AdminStatsBucket> bucketStats(
      Instant from, Instant to, BucketSize bucketSize, List<AgentMessageMetric> events) {
    TreeMap<Instant, MutableBucket> buckets = new TreeMap<>();
    Instant cursor = bucketStart(from, bucketSize);
    while (cursor.isBefore(to)) {
      buckets.put(cursor, new MutableBucket(cursor, bucketEnd(cursor, bucketSize)));
      cursor = bucketEnd(cursor, bucketSize);
    }
    for (AgentMessageMetric event : events) {
      Instant start = bucketStart(event.occurredAt(), bucketSize);
      MutableBucket bucket =
          buckets.computeIfAbsent(start, key -> new MutableBucket(key, bucketEnd(key, bucketSize)));
      bucket.messageCount++;
      bucket.users.add(event.userKeyHash());
      BucketModelKey modelKey = new BucketModelKey(event.modelKey(), event.modelLabel());
      bucket.modelCounts.merge(modelKey, 1L, Long::sum);
    }
    return buckets.values().stream().map(MutableBucket::toResponse).toList();
  }

  private BucketSize chooseBucketSize(Instant from, Instant to) {
    Duration duration = Duration.between(from, to);
    return duration.compareTo(Duration.ofHours(48)) <= 0 ? BucketSize.HOUR : BucketSize.DAY;
  }

  private Instant bucketStart(Instant instant, BucketSize bucketSize) {
    if (bucketSize == BucketSize.HOUR) {
      return instant.truncatedTo(ChronoUnit.HOURS);
    }
    return instant.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  private Instant bucketEnd(Instant start, BucketSize bucketSize) {
    return bucketSize == BucketSize.HOUR
        ? start.plus(1, ChronoUnit.HOURS)
        : start.plus(1, ChronoUnit.DAYS);
  }

  private String hashNullable(String value) {
    String trimmed = StringUtils.trimToNull(value);
    return trimmed == null ? null : hash(trimmed);
  }

  private String hash(String value) {
    return DigestUtils.sha256Hex(value);
  }

  private String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  private double successRate(long successfulCalls, long totalCalls) {
    return totalCalls == 0 ? 0.0 : (double) successfulCalls / totalCalls;
  }

  private MetricContext metricContext(IncomingMessage message) {
    ModelAccessService.ModelAccess modelAccess = modelAccessService.resolve(message);
    String userKey =
        accountResolver == null
            ? null
            : accountResolver
                .resolveOrCreate(message)
                .map(resolved -> resolved.account().getAccountId())
                .orElse(null);
    userKey = firstNonBlank(userKey, message.sender(), message.chatGuid(), "unknown");
    return new MetricContext(hash(userKey), modelAccess);
  }

  private boolean isCanaryAccount(IncomingMessage message) {
    if (canaryService == null || message == null) {
      return false;
    }
    try {
      return canaryService.isCanaryAccount(message);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private enum BucketSize {
    HOUR("hour"),
    DAY("day");

    private final String value;

    BucketSize(String value) {
      this.value = value;
    }
  }

  private record BucketModelKey(String modelKey, String modelLabel) {}

  private record MetricContext(String userKeyHash, ModelAccessService.ModelAccess modelAccess) {}

  private static class MutableSenderStats {
    private final String accountKeyHash;
    private long messageCount;
    private Instant lastSeenAt;
    private final Map<BucketModelKey, Long> modelCounts = new HashMap<>();

    private MutableSenderStats(String accountKeyHash) {
      this.accountKeyHash = accountKeyHash;
    }

    private String accountKeyHash() {
      return accountKeyHash;
    }

    private long messageCount() {
      return messageCount;
    }

    private void record(AgentMessageMetric event) {
      messageCount++;
      if (lastSeenAt == null || event.occurredAt().isAfter(lastSeenAt)) {
        lastSeenAt = event.occurredAt();
      }
      BucketModelKey modelKey = new BucketModelKey(event.modelKey(), event.modelLabel());
      modelCounts.merge(modelKey, 1L, Long::sum);
    }

    private AdminSenderStats toResponse(long totalMessages) {
      List<AdminBucketModelStats> models =
          modelCounts.entrySet().stream()
              .sorted(Map.Entry.<BucketModelKey, Long>comparingByValue().reversed())
              .map(
                  entry ->
                      new AdminBucketModelStats()
                          .modelKey(entry.getKey().modelKey())
                          .modelLabel(entry.getKey().modelLabel())
                          .messageCount(entry.getValue()))
              .toList();
      return new AdminSenderStats()
          .accountKeyHash(accountKeyHash)
          .accountBucket(accountBucket(accountKeyHash))
          .messageCount(messageCount)
          .percentage(totalMessages == 0 ? 0.0 : (double) messageCount / totalMessages)
          .lastSeenAt(offset(lastSeenAt))
          .models(models);
    }

    private String accountBucket(String accountKeyHash) {
      if (accountKeyHash == null || accountKeyHash.isBlank()) {
        return "unknown";
      }
      return StringUtils.truncate(accountKeyHash, ACCOUNT_BUCKET_PREFIX_LENGTH);
    }
  }

  private static class MutableBucket {
    private final Instant start;
    private final Instant end;
    private long messageCount;
    private final Set<String> users = new java.util.HashSet<>();
    private final Map<BucketModelKey, Long> modelCounts = new HashMap<>();

    private MutableBucket(Instant start, Instant end) {
      this.start = start;
      this.end = end;
    }

    private AdminStatsBucket toResponse() {
      List<AdminBucketModelStats> models =
          modelCounts.entrySet().stream()
              .sorted(Map.Entry.<BucketModelKey, Long>comparingByValue().reversed())
              .map(
                  entry ->
                      new AdminBucketModelStats()
                          .modelKey(entry.getKey().modelKey())
                          .modelLabel(entry.getKey().modelLabel())
                          .messageCount(entry.getValue()))
              .toList();
      return new AdminStatsBucket()
          .bucketStart(offset(start))
          .bucketEnd(offset(end))
          .messageCount(messageCount)
          .activeUsers((long) users.size())
          .models(models);
    }
  }
}
