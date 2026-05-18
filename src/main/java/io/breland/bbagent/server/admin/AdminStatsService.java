package io.breland.bbagent.server.admin;

import io.breland.bbagent.generated.model.AdminBucketModelStats;
import io.breland.bbagent.generated.model.AdminModelStats;
import io.breland.bbagent.generated.model.AdminSenderStats;
import io.breland.bbagent.generated.model.AdminStatsBucket;
import io.breland.bbagent.generated.model.AdminStatsPeriod;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricEntity;
import io.breland.bbagent.server.agent.persistence.metrics.AgentMessageMetricRepository;
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
public class AdminStatsService {
  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int TOP_SENDER_LIMIT = 10;
  private static final int ACCOUNT_BUCKET_PREFIX_LENGTH = 12;

  private final AgentMessageMetricRepository repository;
  private final ModelAccessService modelAccessService;
  private final AgentAccountResolver accountResolver;

  public AdminStatsService(
      AgentMessageMetricRepository repository,
      ModelAccessService modelAccessService,
      @Nullable AgentAccountResolver accountResolver) {
    this.repository = repository;
    this.modelAccessService = modelAccessService;
    this.accountResolver = accountResolver;
  }

  @Transactional
  public void recordAcceptedMessage(
      IncomingMessage message, AgentWorkflowProperties.Mode workflowMode) {
    if (message == null) {
      return;
    }
    ModelAccessService.ModelAccess modelAccess = modelAccessService.resolve(message);
    String userKey =
        accountResolver == null
            ? null
            : accountResolver
                .resolveOrCreate(message)
                .map(resolved -> resolved.account().getAccountId())
                .orElse(null);
    userKey = firstNonBlank(userKey, message.sender(), message.chatGuid());
    if (userKey == null) {
      userKey = "unknown";
    }
    Instant now = Instant.now();
    repository.save(
        new AgentMessageMetricEntity(
            UUID.randomUUID().toString(),
            now,
            firstNonBlank(message.transportOrDefault(), "unknown"),
            StringUtils.trimToNull(message.messageGuid()),
            hashNullable(message.chatGuid()),
            hash(userKey),
            firstNonBlank(modelAccess.currentModelKey(), "unknown"),
            firstNonBlank(
                modelAccess.currentModelLabel(), modelAccess.currentModelKey(), "Unknown"),
            firstNonBlank(modelAccess.responsesModel(), "unknown"),
            firstNonBlank(modelAccess.plan(), "standard"),
            modelAccess.premium(),
            workflowMode == null ? "INLINE" : workflowMode.name(),
            now));
  }

  @Transactional(readOnly = true)
  public AdminStatsResponse getStatistics(Instant from, Instant to) {
    AgentMessageMetricRepository.TotalProjection totals = repository.summarize(from, to);
    long messageCount = valueOrZero(totals == null ? null : totals.getMessageCount());
    long activeUsers = valueOrZero(totals == null ? null : totals.getActiveUsers());
    BucketSize bucketSize = chooseBucketSize(from, to);
    List<AgentMessageMetricEntity> events =
        repository.findAllByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAsc(
            from, to);

    return new AdminStatsResponse()
        .period(
            new AdminStatsPeriod()
                .from(offset(from))
                .to(offset(to))
                .bucketSize(AdminStatsPeriod.BucketSizeEnum.fromValue(bucketSize.value)))
        .totalMessages(messageCount)
        .activeUsers(activeUsers)
        .averageMessagesPerUser(activeUsers == 0 ? 0.0 : (double) messageCount / activeUsers)
        .models(modelStats(from, to, messageCount))
        .senders(senderStats(events, messageCount))
        .timeline(bucketStats(from, to, bucketSize, events));
  }

  private List<AdminModelStats> modelStats(Instant from, Instant to, long totalMessages) {
    return repository.summarizeByModel(from, to).stream()
        .map(
            row ->
                new AdminModelStats()
                    .modelKey(firstNonBlank(row.getModelKey(), "unknown"))
                    .modelLabel(firstNonBlank(row.getModelLabel(), row.getModelKey(), "Unknown"))
                    .responsesModel(firstNonBlank(row.getResponsesModel(), "unknown"))
                    .plan(firstNonBlank(row.getPlan(), "standard"))
                    .isPremium(Boolean.TRUE.equals(row.getPremium()))
                    .messageCount(valueOrZero(row.getMessageCount()))
                    .activeUsers(valueOrZero(row.getActiveUsers()))
                    .percentage(
                        totalMessages == 0
                            ? 0.0
                            : (double) valueOrZero(row.getMessageCount()) / totalMessages))
        .toList();
  }

  private List<AdminSenderStats> senderStats(
      List<AgentMessageMetricEntity> events, long totalMessages) {
    Map<String, MutableSenderStats> senders = new HashMap<>();
    for (AgentMessageMetricEntity event : events) {
      String accountKeyHash = firstNonBlank(event.getUserKeyHash(), "unknown");
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
      Instant from, Instant to, BucketSize bucketSize, List<AgentMessageMetricEntity> events) {
    TreeMap<Instant, MutableBucket> buckets = new TreeMap<>();
    Instant cursor = bucketStart(from, bucketSize);
    while (cursor.isBefore(to)) {
      buckets.put(cursor, new MutableBucket(cursor, bucketEnd(cursor, bucketSize)));
      cursor = bucketEnd(cursor, bucketSize);
    }
    for (AgentMessageMetricEntity event : events) {
      Instant start = bucketStart(event.getOccurredAt(), bucketSize);
      MutableBucket bucket =
          buckets.computeIfAbsent(start, key -> new MutableBucket(key, bucketEnd(key, bucketSize)));
      bucket.messageCount++;
      bucket.users.add(event.getUserKeyHash());
      BucketModelKey modelKey = new BucketModelKey(event.getModelKey(), event.getModelLabel());
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

  private long valueOrZero(Long value) {
    return value == null ? 0L : value;
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

  private enum BucketSize {
    HOUR("hour"),
    DAY("day");

    private final String value;

    BucketSize(String value) {
      this.value = value;
    }
  }

  private record BucketModelKey(String modelKey, String modelLabel) {}

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

    private void record(AgentMessageMetricEntity event) {
      messageCount++;
      if (lastSeenAt == null || event.getOccurredAt().isAfter(lastSeenAt)) {
        lastSeenAt = event.getOccurredAt();
      }
      BucketModelKey modelKey = new BucketModelKey(event.getModelKey(), event.getModelLabel());
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
          .lastSeenAt(OffsetDateTime.ofInstant(lastSeenAt, ZoneOffset.UTC))
          .models(models);
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
          .bucketStart(OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
          .bucketEnd(OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
          .messageCount(messageCount)
          .activeUsers((long) users.size())
          .models(models);
    }
  }
}
