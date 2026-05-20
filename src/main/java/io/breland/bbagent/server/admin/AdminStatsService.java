package io.breland.bbagent.server.admin;

import io.breland.bbagent.generated.model.AdminBucketModelStats;
import io.breland.bbagent.generated.model.AdminModelStats;
import io.breland.bbagent.generated.model.AdminSenderStats;
import io.breland.bbagent.generated.model.AdminStatsBucket;
import io.breland.bbagent.generated.model.AdminStatsPeriod;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.generated.model.AdminToolAccountTypeStats;
import io.breland.bbagent.generated.model.AdminToolStats;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.metrics.AgentMessageMetric;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import io.breland.bbagent.server.metrics.AgentMetricsStore;
import io.breland.bbagent.server.metrics.AgentToolMetric;
import io.breland.bbagent.server.metrics.AgentToolMetricEvent;
import io.breland.bbagent.server.metrics.MessageMetricTotals;
import io.breland.bbagent.server.metrics.ToolMetricTotals;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStatsService implements AgentMetricsService {
  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int TOP_SENDER_LIMIT = 10;
  private static final int ACCOUNT_BUCKET_PREFIX_LENGTH = 12;

  private final AgentMetricsStore metricsStore;
  private final ModelAccessService modelAccessService;
  private final AgentAccountResolver accountResolver;

  public AdminStatsService(
      AgentMetricsStore metricsStore,
      ModelAccessService modelAccessService,
      @Nullable AgentAccountResolver accountResolver) {
    this.metricsStore = metricsStore;
    this.modelAccessService = modelAccessService;
    this.accountResolver = accountResolver;
  }

  @Transactional
  @Override
  public void recordAcceptedMessage(
      IncomingMessage message, AgentWorkflowProperties.Mode workflowMode) {
    if (message == null) {
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
            workflowMode == null ? "INLINE" : workflowMode.name(),
            now));
  }

  @Transactional
  @Override
  public void recordToolCall(AgentToolMetricEvent event) {
    if (event == null || event.message() == null) {
      return;
    }
    IncomingMessage message = event.message();
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
            event.workflowMode() == null ? "INLINE" : event.workflowMode().name(),
            now));
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

  private OffsetDateTime offset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String hashNullable(String value) {
    String trimmed = StringUtils.trimToNull(value);
    return trimmed == null ? null : hash(trimmed);
  }

  private String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing hash algorithm " + HASH_ALGORITHM, e);
    }
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

  private static List<AdminBucketModelStats> bucketModelStats(
      Map<BucketModelKey, Long> modelCounts) {
    return modelCounts.entrySet().stream()
        .sorted(Map.Entry.<BucketModelKey, Long>comparingByValue().reversed())
        .map(
            entry ->
                new AdminBucketModelStats()
                    .modelKey(entry.getKey().modelKey())
                    .modelLabel(entry.getKey().modelLabel())
                    .messageCount(entry.getValue()))
        .toList();
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
      return new AdminSenderStats()
          .accountKeyHash(accountKeyHash)
          .accountBucket(accountBucket(accountKeyHash))
          .messageCount(messageCount)
          .percentage(totalMessages == 0 ? 0.0 : (double) messageCount / totalMessages)
          .lastSeenAt(OffsetDateTime.ofInstant(lastSeenAt, ZoneOffset.UTC))
          .models(bucketModelStats(modelCounts));
    }

    private String accountBucket(String accountKeyHash) {
      if (accountKeyHash == null || accountKeyHash.isBlank()) {
        return "unknown";
      }
      return accountKeyHash.substring(
          0, Math.min(accountKeyHash.length(), ACCOUNT_BUCKET_PREFIX_LENGTH));
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
      return new AdminStatsBucket()
          .bucketStart(OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
          .bucketEnd(OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
          .messageCount(messageCount)
          .activeUsers((long) users.size())
          .models(bucketModelStats(modelCounts));
    }
  }
}
