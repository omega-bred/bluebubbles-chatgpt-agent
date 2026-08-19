package io.breland.bbagent.server.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.AuthorizedGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.DigestWorkClaim;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.GroupQuestionResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.QuestionGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.SummaryMaterial;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConversationDigestService {
  private static final Duration MAX_CATCHUP_RANGE = Duration.ofDays(31);
  private static final int DIGEST_CLAIM_LIMIT = 20;

  private final ConversationMemoryStore store;
  private final ObjectMapper objectMapper;
  private final @Nullable BBHttpClientWrapper bbHttpClientWrapper;
  private final @Nullable ConversationJournalService journalService;
  private final @Nullable ConversationQuestionAnsweringService questionAnsweringService;
  private final Clock clock;
  private final String workerId;
  private final @Nullable OperationalMetricsService metrics;
  private final boolean globallyEnabled;

  @Autowired
  public ConversationDigestService(
      ConversationMemoryStore store,
      ObjectMapper objectMapper,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable ConversationJournalService journalService,
      ConversationQuestionAnsweringService questionAnsweringService,
      @Nullable Clock clock,
      @Nullable OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this(
        store,
        objectMapper,
        bbHttpClientWrapper,
        journalService,
        questionAnsweringService,
        clock == null ? Clock.systemUTC() : clock,
        UUID.randomUUID().toString(),
        metrics,
        globallyEnabled);
  }

  ConversationDigestService(
      ConversationMemoryStore store,
      ObjectMapper objectMapper,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable ConversationJournalService journalService,
      Clock clock,
      String workerId) {
    this(
        store,
        objectMapper,
        bbHttpClientWrapper,
        journalService,
        null,
        clock,
        workerId,
        null,
        true);
  }

  ConversationDigestService(
      ConversationMemoryStore store,
      ObjectMapper objectMapper,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable ConversationJournalService journalService,
      ConversationQuestionAnsweringService questionAnsweringService,
      Clock clock,
      String workerId) {
    this(
        store,
        objectMapper,
        bbHttpClientWrapper,
        journalService,
        questionAnsweringService,
        clock,
        workerId,
        null,
        true);
  }

  ConversationDigestService(
      ConversationMemoryStore store,
      ObjectMapper objectMapper,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable ConversationJournalService journalService,
      Clock clock,
      String workerId,
      @Nullable OperationalMetricsService metrics,
      boolean globallyEnabled) {
    this(
        store,
        objectMapper,
        bbHttpClientWrapper,
        journalService,
        null,
        clock,
        workerId,
        metrics,
        globallyEnabled);
  }

  ConversationDigestService(
      ConversationMemoryStore store,
      ObjectMapper objectMapper,
      @Nullable BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable ConversationJournalService journalService,
      @Nullable ConversationQuestionAnsweringService questionAnsweringService,
      Clock clock,
      String workerId,
      @Nullable OperationalMetricsService metrics,
      boolean globallyEnabled) {
    this.store = store;
    this.objectMapper = objectMapper;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.journalService = journalService;
    this.questionAnsweringService = questionAnsweringService;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.workerId = workerId;
    this.metrics = metrics;
    this.globallyEnabled = globallyEnabled;
  }

  public CatchupResult catchUp(
      String accountId, @Nullable String groupHint, Instant requestedFrom, Instant requestedTo) {
    Instant startedAt = clock.instant();
    try {
      CatchupResult result = catchUpInternal(accountId, groupHint, requestedFrom, requestedTo);
      recordCatchup(true, null, startedAt);
      return result;
    } catch (RuntimeException e) {
      recordCatchup(false, OperationalMetricsService.failureType(e), startedAt);
      throw e;
    }
  }

  private CatchupResult catchUpInternal(
      String accountId, @Nullable String groupHint, Instant requestedFrom, Instant requestedTo) {
    if (!globallyEnabled) {
      return new CatchupResult(List.of(), List.of());
    }
    if (StringUtils.isBlank(accountId) || requestedFrom == null || requestedTo == null) {
      throw new IllegalArgumentException("account and catch-up range are required");
    }
    Instant now = clock.instant();
    if (requestedTo.isAfter(now) || !requestedFrom.isBefore(requestedTo)) {
      throw new IllegalArgumentException("catch-up range must be ordered and not in the future");
    }
    Instant from = requestedFrom;
    if (Duration.between(from, requestedTo).compareTo(MAX_CATCHUP_RANGE) > 0) {
      from = requestedTo.minus(MAX_CATCHUP_RANGE);
    }

    List<AuthorizedGroup> authorizedGroups =
        store.findAuthorizedGroups(accountId, from, requestedTo);
    GroupSelection selection = selectGroups(authorizedGroups, groupHint);
    if (!selection.disambiguationOptions().isEmpty()) {
      return new CatchupResult(List.of(), selection.disambiguationOptions());
    }
    List<CatchupGroup> groups = new ArrayList<>();
    for (AuthorizedGroup group : selection.groups()) {
      groups.add(buildCatchupGroup(accountId, group, from, requestedTo));
    }
    return new CatchupResult(List.copyOf(groups), List.of());
  }

  public CatchupResult catchUpForChat(
      String accountId,
      String transport,
      String chatGuid,
      Instant requestedFrom,
      Instant requestedTo) {
    Instant startedAt = clock.instant();
    try {
      CatchupResult result =
          catchUpForChatInternal(accountId, transport, chatGuid, requestedFrom, requestedTo);
      recordCatchup(true, null, startedAt);
      return result;
    } catch (RuntimeException e) {
      recordCatchup(false, OperationalMetricsService.failureType(e), startedAt);
      throw e;
    }
  }

  private CatchupResult catchUpForChatInternal(
      String accountId,
      String transport,
      String chatGuid,
      Instant requestedFrom,
      Instant requestedTo) {
    if (!globallyEnabled) {
      return new CatchupResult(List.of(), List.of());
    }
    if (StringUtils.isAnyBlank(accountId, transport, chatGuid)
        || requestedFrom == null
        || requestedTo == null) {
      throw new IllegalArgumentException("account, current chat, and catch-up range are required");
    }
    Instant now = clock.instant();
    if (requestedTo.isAfter(now) || !requestedFrom.isBefore(requestedTo)) {
      throw new IllegalArgumentException("catch-up range must be ordered and not in the future");
    }
    AuthorizedGroup group =
        store
            .findCurrentlyAuthorizedGroup(accountId, transport, chatGuid, requestedTo)
            .orElse(null);
    if (group == null) {
      return new CatchupResult(List.of(), List.of());
    }
    return new CatchupResult(
        List.of(buildCatchupGroup(accountId, group, requestedFrom, requestedTo)), List.of());
  }

  public GroupQuestionResult answerQuestion(
      String accountId,
      @Nullable String groupHint,
      String question,
      @Nullable Instant from,
      Instant to,
      @Nullable String timezone) {
    if (!globallyEnabled) {
      return new GroupQuestionResult(List.of(), List.of());
    }
    validateQuestionRequest(accountId, question, from, to, timezone);
    GroupSelection selection =
        selectQuestionGroup(store.findCurrentlyAuthorizedGroups(accountId, to), groupHint);
    if (!selection.disambiguationOptions().isEmpty()) {
      return new GroupQuestionResult(List.of(), selection.disambiguationOptions());
    }
    if (questionAnsweringService == null) {
      throw new IllegalStateException("group question answering unavailable");
    }
    List<QuestionGroup> groups =
        selection.groups().stream()
            .map(
                group ->
                    new QuestionGroup(
                        StringUtils.defaultIfBlank(group.displayName(), "Group conversation"),
                        questionAnsweringService.answer(
                            accountId, group, question, from, to, timezone)))
            .toList();
    return new GroupQuestionResult(groups, List.of());
  }

  public GroupQuestionResult answerQuestionForChat(
      String accountId,
      String transport,
      String chatGuid,
      String question,
      @Nullable Instant from,
      Instant to,
      @Nullable String timezone) {
    if (!globallyEnabled) {
      return new GroupQuestionResult(List.of(), List.of());
    }
    if (StringUtils.isAnyBlank(transport, chatGuid)) {
      throw new IllegalArgumentException("current chat is required");
    }
    validateQuestionRequest(accountId, question, from, to, timezone);
    AuthorizedGroup group =
        store.findCurrentlyAuthorizedGroup(accountId, transport, chatGuid, to).orElse(null);
    if (group == null) {
      return new GroupQuestionResult(List.of(), List.of());
    }
    if (questionAnsweringService == null) {
      throw new IllegalStateException("group question answering unavailable");
    }
    return new GroupQuestionResult(
        List.of(
            new QuestionGroup(
                StringUtils.defaultIfBlank(group.displayName(), "Group conversation"),
                questionAnsweringService.answer(accountId, group, question, from, to, timezone))),
        List.of());
  }

  private void validateQuestionRequest(
      String accountId,
      String question,
      @Nullable Instant from,
      Instant to,
      @Nullable String timezone) {
    if (StringUtils.isAnyBlank(accountId, question)
        || to == null
        || to.isAfter(clock.instant())
        || (from != null && !from.isBefore(to))) {
      throw new IllegalArgumentException("question range must be ordered and not in the future");
    }
    if (StringUtils.isNotBlank(timezone)) {
      ZoneId.of(timezone.trim());
    }
  }

  public CatchupResult catchUpForConversation(
      String accountId, String conversationId, Instant requestedFrom, Instant requestedTo) {
    Instant startedAt = clock.instant();
    try {
      CatchupResult result =
          catchUpForConversationInternal(accountId, conversationId, requestedFrom, requestedTo);
      recordCatchup(true, null, startedAt);
      return result;
    } catch (RuntimeException e) {
      recordCatchup(false, OperationalMetricsService.failureType(e), startedAt);
      throw e;
    }
  }

  private CatchupResult catchUpForConversationInternal(
      String accountId, String conversationId, Instant requestedFrom, Instant requestedTo) {
    if (!globallyEnabled) {
      return new CatchupResult(List.of(), List.of());
    }
    if (StringUtils.isBlank(conversationId)) {
      throw new IllegalArgumentException("conversation is required");
    }
    Instant now = clock.instant();
    if (StringUtils.isBlank(accountId)
        || requestedFrom == null
        || requestedTo == null
        || requestedTo.isAfter(now)
        || !requestedFrom.isBefore(requestedTo)) {
      throw new IllegalArgumentException("catch-up range must be ordered and not in the future");
    }
    Instant from = requestedFrom;
    if (Duration.between(from, requestedTo).compareTo(MAX_CATCHUP_RANGE) > 0) {
      from = requestedTo.minus(MAX_CATCHUP_RANGE);
    }
    final Instant authorizedFrom = from;
    Optional<AuthorizedGroup> group =
        store.findAuthorizedGroups(accountId, authorizedFrom, requestedTo).stream()
            .filter(candidate -> conversationId.equals(candidate.conversationId()))
            .findFirst();
    if (group.isEmpty()) {
      return new CatchupResult(List.of(), List.of());
    }
    return new CatchupResult(
        List.of(buildCatchupGroup(accountId, group.get(), authorizedFrom, requestedTo)), List.of());
  }

  public Instant currentTime() {
    return clock.instant();
  }

  @Scheduled(cron = "${bbagent.memory.group.reconciliation-cron:0 15 3 * * *}", zone = "UTC")
  public void reconcilePreviousDay() {
    if (!globallyEnabled) {
      return;
    }
    Instant now = clock.instant();
    boolean success = true;
    try {
      LocalDate previousDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
      Instant periodStart = previousDay.atStartOfDay().toInstant(ZoneOffset.UTC);
      Instant periodEnd = previousDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
      for (ConversationRecord conversation : store.findMemoryEnabledConversations()) {
        if (conversation.memoryEnabledAt().isBefore(periodEnd)) {
          store.seedDigestWork(conversation.conversationId(), periodStart, periodEnd, now);
        }
      }
      for (DigestWorkClaim claim : store.claimDueDigestWork(workerId, now, DIGEST_CLAIM_LIMIT)) {
        success = reconcile(claim, now) && success;
      }
      if (metrics != null) {
        metrics.recordMemoryDigest(
            "reconcile",
            success,
            success ? null : "digest_work_failed",
            Duration.between(now, clock.instant()));
      }
    } catch (RuntimeException e) {
      if (metrics != null) {
        metrics.recordMemoryDigest(
            "reconcile",
            false,
            OperationalMetricsService.failureType(e),
            Duration.between(now, clock.instant()));
      }
      throw e;
    }
  }

  private CatchupGroup buildCatchupGroup(
      String accountId, AuthorizedGroup group, Instant from, Instant to) {
    Instant todayStart =
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    List<SummaryMaterial> selected = new ArrayList<>();
    List<SummaryMaterial> digests =
        store.findAuthorizedDigests(group.conversationId(), accountId, from, to).stream()
            .filter(digest -> !digest.windowStart().isBefore(from))
            .filter(digest -> !digest.windowEnd().isAfter(to))
            .filter(digest -> !digest.windowEnd().isAfter(todayStart))
            .toList();
    selected.addAll(digests);
    for (SummaryMaterial segment :
        store.findAuthorizedSegments(group.conversationId(), accountId, from, to)) {
      boolean coveredByDigest =
          digests.stream()
              .anyMatch(
                  digest ->
                      !segment.windowStart().isBefore(digest.windowStart())
                          && !segment.windowEnd().isAfter(digest.windowEnd()));
      if (!coveredByDigest) {
        selected.add(segment);
      }
    }
    selected.sort(
        Comparator.comparing(SummaryMaterial::windowStart)
            .thenComparing(SummaryMaterial::summaryId));
    List<String> developments =
        selected.stream()
            .map(SummaryMaterial::summary)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
    List<String> decisions =
        store.findAuthorizedDecisions(group.conversationId(), accountId, from, to, clock.instant());
    List<String> openQuestions = extractOpenQuestions(selected);
    Instant coverageThrough =
        selected.stream()
            .map(SummaryMaterial::coverageThrough)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .or(
                () ->
                    store
                        .findCheckpoint(group.conversationId())
                        .map(ConversationMemoryModels.ExtractionCheckpoint::lastProcessedAt))
            .map(value -> value.isAfter(to) ? to : value)
            .orElse(from);
    return new CatchupGroup(
        StringUtils.defaultIfBlank(group.displayName(), "Group conversation"),
        String.join(" ", developments),
        developments,
        decisions,
        openQuestions,
        from,
        to,
        coverageThrough);
  }

  private GroupSelection selectQuestionGroup(
      List<AuthorizedGroup> authorizedGroups, @Nullable String groupHint) {
    GroupSelection selection = selectGroups(authorizedGroups, groupHint);
    if (selection.groups().size() == 1 || !selection.disambiguationOptions().isEmpty()) {
      return selection;
    }
    if (normalizeGroupHint(groupHint) == null && authorizedGroups.size() > 1) {
      return new GroupSelection(List.of(), disambiguationOptions(authorizedGroups));
    }
    return new GroupSelection(List.of(), List.of());
  }

  private GroupSelection selectGroups(
      List<AuthorizedGroup> authorizedGroups, @Nullable String groupHint) {
    String normalizedHint = normalizeGroupHint(groupHint);
    if (normalizedHint == null) {
      return new GroupSelection(authorizedGroups, List.of());
    }
    List<AuthorizedGroup> exact =
        authorizedGroups.stream()
            .filter(group -> normalizedName(group.displayName()).equals(normalizedHint))
            .toList();
    List<AuthorizedGroup> matches =
        exact.isEmpty()
            ? authorizedGroups.stream()
                .filter(group -> normalizedName(group.displayName()).contains(normalizedHint))
                .toList()
            : exact;
    if (matches.size() > 1) {
      return new GroupSelection(List.of(), disambiguationOptions(matches));
    }
    return new GroupSelection(matches, List.of());
  }

  private List<String> disambiguationOptions(List<AuthorizedGroup> groups) {
    return groups.stream()
        .map(
            group ->
                StringUtils.defaultIfBlank(group.displayName(), "Group conversation")
                    + " (last active "
                    + DateTimeFormatter.ISO_INSTANT.format(group.lastActivityAt())
                    + ")")
        .toList();
  }

  private String normalizeGroupHint(@Nullable String hint) {
    String normalized = StringUtils.trimToNull(hint);
    if (normalized == null) {
      return null;
    }
    int suffix = normalized.indexOf(" (last active ");
    if (suffix > 0) {
      normalized = normalized.substring(0, suffix);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private String normalizedName(@Nullable String value) {
    return StringUtils.defaultString(value, "Group conversation").trim().toLowerCase(Locale.ROOT);
  }

  private List<String> extractOpenQuestions(List<SummaryMaterial> materials) {
    List<String> questions = new ArrayList<>();
    for (SummaryMaterial material : materials) {
      try {
        JsonNode payload = objectMapper.readTree(material.itemPayload());
        if (payload == null || !payload.isArray()) {
          continue;
        }
        for (JsonNode item : payload) {
          if ("PROVISIONAL".equals(item.path("status").asText())
              && StringUtils.isNotBlank(item.path("text").asText())) {
            questions.add(item.path("text").asText());
          }
        }
      } catch (Exception ignored) {
        // Invalid historical payloads are omitted rather than exposed or logged.
      }
    }
    return questions.stream().distinct().toList();
  }

  private boolean reconcile(DigestWorkClaim claim, Instant now) {
    try {
      refreshJournalFromBlueBubbles(claim);
      List<JournalMessage> messages =
          store.findMessages(
              claim.conversationId(), claim.periodStart(), claim.periodEnd().minusNanos(1));
      List<SummaryMaterial> segments =
          store.findSegments(claim.conversationId(), claim.periodStart(), claim.periodEnd());
      Instant journalCoverage =
          messages.stream()
              .map(JournalMessage::sourceTimestamp)
              .max(Instant::compareTo)
              .orElse(claim.periodStart());
      Instant segmentCoverage =
          segments.stream()
              .map(SummaryMaterial::coverageThrough)
              .max(Instant::compareTo)
              .orElse(claim.periodStart());
      if (journalCoverage.isAfter(segmentCoverage)) {
        store.scheduleExtraction(claim.conversationId(), now);
        store.failDigestWork(claim, now, "segment_coverage_gap");
        return false;
      }
      String summary =
          segments.isEmpty()
              ? "No captured group developments."
              : String.join(
                  " ",
                  segments.stream()
                      .map(SummaryMaterial::summary)
                      .filter(StringUtils::isNotBlank)
                      .distinct()
                      .toList());
      Instant coverageThrough =
          segmentCoverage.isAfter(claim.periodEnd()) ? claim.periodEnd() : segmentCoverage;
      store.saveDigest(
          claim,
          new DigestBatch(
              claim.conversationId(),
              claim.periodStart(),
              claim.periodEnd(),
              summary,
              combineItemPayloads(segments),
              corpusHash(messages),
              coverageThrough,
              segments.stream().map(SummaryMaterial::summaryId).toList(),
              now));
      return true;
    } catch (RuntimeException e) {
      store.failDigestWork(claim, now, OperationalMetricsService.failureType(e));
      return false;
    }
  }

  private void recordCatchup(boolean success, @Nullable String failureType, Instant startedAt) {
    if (metrics != null) {
      metrics.recordMemoryCatchup(
          success, failureType, Duration.between(startedAt, clock.instant()));
    }
  }

  private void refreshJournalFromBlueBubbles(DigestWorkClaim claim) {
    if (bbHttpClientWrapper == null || journalService == null) {
      return;
    }
    Optional<ConversationRecord> conversationValue = store.findConversation(claim.conversationId());
    if (conversationValue.isEmpty()
        || !IncomingMessage.TRANSPORT_BLUEBUBBLES.equalsIgnoreCase(
            conversationValue.get().transport())) {
      return;
    }
    ConversationRecord conversation = conversationValue.get();
    int offset = 0;
    int limit = 500;
    while (true) {
      var page =
          bbHttpClientWrapper.getMessagesInChat(
              conversation.externalConversationId(),
              claim.periodStart(),
              claim.periodEnd(),
              offset,
              limit,
              "ASC");
      for (var rawMessage : page) {
        IncomingMessage message = IncomingMessage.create(rawMessage);
        if (message != null) {
          journalService.recordEligibleMessage(
              new IncomingMessage(
                  message.transport(),
                  conversation.externalConversationId(),
                  message.messageGuid(),
                  message.threadOriginatorGuid(),
                  message.text(),
                  message.fromMe(),
                  message.service(),
                  message.sender(),
                  true,
                  message.timestamp(),
                  message.attachments(),
                  message.balloonBundleId(),
                  message.associatedMessageGuid(),
                  message.replyToGuid(),
                  message.isSystemMessage()));
        }
      }
      if (page.size() < limit) {
        break;
      }
      offset += page.size();
    }
  }

  private String combineItemPayloads(List<SummaryMaterial> segments) {
    ArrayNode combined = objectMapper.createArrayNode();
    for (SummaryMaterial segment : segments) {
      try {
        JsonNode payload = objectMapper.readTree(segment.itemPayload());
        if (payload != null && payload.isArray()) {
          payload.forEach(combined::add);
        }
      } catch (Exception ignored) {
        // Invalid historical payloads are skipped.
      }
    }
    return combined.toString();
  }

  private String corpusHash(List<JournalMessage> messages) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (JournalMessage message : messages) {
        digest.update(message.messageGuid().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(message.contentHash().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private record GroupSelection(List<AuthorizedGroup> groups, List<String> disambiguationOptions) {}
}
