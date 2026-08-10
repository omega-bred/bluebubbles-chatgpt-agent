package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.server.TimeSupport;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistorySource;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindow;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindowCursor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationQuestionHistoryRetriever {
  private static final int MAX_SOURCE_CALLS = 100;
  private static final String HISTORY_LIMIT = "history_limit";
  private static final String TIME_LIMIT = "time_limit";
  private static final String SOURCE_UNAVAILABLE = "source_unavailable";

  private final BBHttpClientWrapper bb;
  private final ConversationMemoryStore store;
  private final ConversationHistoryMessageMapper mapper;
  private final int maxHistoryPages;
  private final Clock clock;

  @Autowired
  public ConversationQuestionHistoryRetriever(
      BBHttpClientWrapper bb,
      ConversationMemoryStore store,
      ConversationHistoryMessageMapper mapper,
      @Value("${bbagent.memory.group.qa.max-history-pages}") int maxHistoryPages) {
    this(bb, store, mapper, maxHistoryPages, Clock.systemUTC());
  }

  ConversationQuestionHistoryRetriever(
      BBHttpClientWrapper bb,
      ConversationMemoryStore store,
      ConversationHistoryMessageMapper mapper,
      int maxHistoryPages,
      Clock clock) {
    this.bb = Objects.requireNonNull(bb, "bb");
    this.store = Objects.requireNonNull(store, "store");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    if (maxHistoryPages < 1 || maxHistoryPages > MAX_SOURCE_CALLS) {
      throw new IllegalArgumentException("max history pages must be between 1 and 100");
    }
    this.maxHistoryPages = maxHistoryPages;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public HistoryWindow retrieveWindow(
      RetrievalRequest request, @Nullable HistoryWindowCursor cursor, int windowMessageCount) {
    Objects.requireNonNull(request, "request");
    if (windowMessageCount < 1 || windowMessageCount > 500) {
      throw new IllegalArgumentException("window message count must be between 1 and 500");
    }
    List<Bounds> newestFirst =
        new ArrayList<>(authorizedBounds(request, new Bounds(request.from(), request.to())));
    Collections.reverse(newestFirst);
    if (newestFirst.isEmpty()) {
      return new HistoryWindow(List.of(), null, true, true, null, 0);
    }
    if (cursor != null && (!isBlueBubbles(request) && cursor.source() != HistorySource.JOURNAL)) {
      throw new IllegalArgumentException("history cursor source does not match conversation");
    }
    if (!isBlueBubbles(request) || (cursor != null && cursor.source() == HistorySource.JOURNAL)) {
      String partialReason = isBlueBubbles(request) && cursor != null ? SOURCE_UNAVAILABLE : null;
      return retrieveJournalWindow(request, newestFirst, cursor, windowMessageCount, partialReason);
    }
    return retrieveBlueBubblesWindow(request, newestFirst, cursor, windowMessageCount);
  }

  private HistoryWindow retrieveJournalWindow(
      RetrievalRequest request,
      List<Bounds> newestFirst,
      @Nullable HistoryWindowCursor cursor,
      int windowMessageCount,
      @Nullable String inheritedPartialReason) {
    return retrieveJournalWindow(
        request,
        newestFirst,
        cursor,
        windowMessageCount,
        new ArrayList<>(windowMessageCount),
        new RawGuidTracker(),
        new CallBudget(),
        inheritedPartialReason);
  }

  private HistoryWindow retrieveJournalWindow(
      RetrievalRequest request,
      List<Bounds> newestFirst,
      @Nullable HistoryWindowCursor cursor,
      int windowMessageCount,
      List<QuestionMessage> accepted,
      RawGuidTracker seenRawGuids,
      CallBudget budget,
      @Nullable String inheritedPartialReason) {
    int membershipIndex = cursor == null ? 0 : cursor.membershipIndex();
    Instant beforeTimestamp = cursor == null ? null : cursor.journalBeforeTimestamp();
    String beforeGuid = cursor == null ? null : cursor.journalBeforeGuid();
    if (membershipIndex >= newestFirst.size()) {
      throw new IllegalArgumentException("history cursor membership is outside the request");
    }
    while (accepted.size() < windowMessageCount && membershipIndex < newestFirst.size()) {
      Bounds bound = newestFirst.get(membershipIndex);
      Duration remaining = budget.reserve(request.deadline());
      if (remaining == null) {
        return historyWindow(
            accepted,
            new HistoryWindowCursor(
                HistorySource.JOURNAL, membershipIndex, 0, beforeTimestamp, beforeGuid),
            false,
            partialReason(budget, inheritedPartialReason),
            budget.pages());
      }
      int requested = Math.min(500, windowMessageCount - accepted.size());
      List<JournalMessage> page =
          Objects.requireNonNull(
              store.findMessagePageDescending(
                  request.conversation().conversationId(),
                  bound.from(),
                  bound.to(),
                  beforeTimestamp,
                  beforeGuid,
                  requested,
                  remaining),
              "journal history window returned no page");
      for (JournalMessage raw : page) {
        mapAuthorized(raw, request, bound, seenRawGuids)
            .ifPresent(message -> addWindowMessage(accepted, message));
      }
      if (!page.isEmpty()) {
        JournalMessage oldest = page.getLast();
        if (oldest == null
            || oldest.sourceTimestamp() == null
            || StringUtils.isBlank(oldest.messageGuid())) {
          throw new IllegalStateException("journal returned an invalid page cursor");
        }
        beforeTimestamp = oldest.sourceTimestamp();
        beforeGuid = oldest.messageGuid();
      }
      if (page.size() < requested) {
        membershipIndex++;
        beforeTimestamp = null;
        beforeGuid = null;
      }
    }
    boolean exhausted = membershipIndex >= newestFirst.size();
    HistoryWindowCursor nextCursor =
        exhausted
            ? null
            : new HistoryWindowCursor(
                HistorySource.JOURNAL, membershipIndex, 0, beforeTimestamp, beforeGuid);
    return historyWindow(
        accepted,
        nextCursor,
        exhausted,
        partialReason(budget, inheritedPartialReason),
        budget.pages());
  }

  private HistoryWindow retrieveBlueBubblesWindow(
      RetrievalRequest request,
      List<Bounds> newestFirst,
      @Nullable HistoryWindowCursor cursor,
      int windowMessageCount) {
    int membershipIndex = cursor == null ? 0 : cursor.membershipIndex();
    int rawOffset = cursor == null ? 0 : cursor.rawOffset();
    Instant journalBeforeTimestamp = cursor == null ? null : cursor.journalBeforeTimestamp();
    String journalBeforeGuid = cursor == null ? null : cursor.journalBeforeGuid();
    if (membershipIndex >= newestFirst.size()) {
      throw new IllegalArgumentException("history cursor membership is outside the request");
    }
    List<QuestionMessage> accepted = new ArrayList<>(windowMessageCount);
    RawGuidTracker seenRawGuids = new RawGuidTracker();
    CallBudget budget = new CallBudget();
    while (accepted.size() < windowMessageCount && membershipIndex < newestFirst.size()) {
      Bounds bound = newestFirst.get(membershipIndex);
      Duration remaining = budget.reserve(request.deadline());
      if (remaining == null) {
        return historyWindow(
            accepted,
            new HistoryWindowCursor(
                HistorySource.BLUEBUBBLES,
                membershipIndex,
                rawOffset,
                journalBeforeTimestamp,
                journalBeforeGuid),
            false,
            budget.partialReason(),
            budget.pages());
      }
      int requested = Math.min(500, windowMessageCount - accepted.size());
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> page;
      try {
        page =
            Objects.requireNonNull(
                bb.getMessagesInChatForQuestion(
                    request.conversation().externalConversationId(),
                    bound.from(),
                    bound.to(),
                    rawOffset,
                    requested,
                    "DESC",
                    remaining),
                "question history window returned no page");
      } catch (RuntimeException ignored) {
        budget.limit(SOURCE_UNAVAILABLE);
        return retrieveJournalWindow(
            request,
            newestFirst,
            new HistoryWindowCursor(
                HistorySource.JOURNAL,
                membershipIndex,
                0,
                journalBeforeTimestamp,
                journalBeforeGuid),
            windowMessageCount,
            accepted,
            seenRawGuids,
            budget,
            SOURCE_UNAVAILABLE);
      }
      for (ApiV1ChatChatGuidMessageGet200ResponseDataInner raw : page) {
        mapAuthorized(raw, request, bound, seenRawGuids)
            .ifPresent(message -> addWindowMessage(accepted, message));
      }
      for (int index = page.size() - 1; index >= 0; index--) {
        ApiV1ChatChatGuidMessageGet200ResponseDataInner raw = page.get(index);
        if (raw != null && StringUtils.isNotBlank(raw.getGuid()) && raw.getDateCreated() != null) {
          Instant rawTimestamp = TimeSupport.epochSecondsOrMillisOrNow(raw.getDateCreated());
          if (authorized(rawTimestamp, request, bound)) {
            journalBeforeTimestamp = rawTimestamp;
            journalBeforeGuid = raw.getGuid();
            break;
          }
        }
      }
      rawOffset += page.size();
      if (page.size() < requested) {
        membershipIndex++;
        rawOffset = 0;
        journalBeforeTimestamp = null;
        journalBeforeGuid = null;
      }
    }
    boolean exhausted = membershipIndex >= newestFirst.size();
    HistoryWindowCursor nextCursor =
        exhausted
            ? null
            : new HistoryWindowCursor(
                HistorySource.BLUEBUBBLES,
                membershipIndex,
                rawOffset,
                journalBeforeTimestamp,
                journalBeforeGuid);
    return historyWindow(accepted, nextCursor, exhausted, null, budget.pages());
  }

  private static @Nullable String partialReason(
      CallBudget budget, @Nullable String inheritedPartialReason) {
    return budget.partialReason() == null ? inheritedPartialReason : budget.partialReason();
  }

  private static void addWindowMessage(List<QuestionMessage> messages, QuestionMessage candidate) {
    for (int index = 0; index < messages.size(); index++) {
      QuestionMessage existing = messages.get(index);
      if (!normalizeGuid(existing.messageGuid()).equals(normalizeGuid(candidate.messageGuid()))) {
        continue;
      }
      if (labelQuality(candidate.participant()) > labelQuality(existing.participant())) {
        messages.set(index, candidate);
      }
      return;
    }
    messages.add(candidate);
  }

  private static HistoryWindow historyWindow(
      List<QuestionMessage> messages,
      @Nullable HistoryWindowCursor nextCursor,
      boolean sourceExhausted,
      @Nullable String partialReason,
      int pageCount) {
    return new HistoryWindow(
        sort(messages),
        nextCursor,
        sourceExhausted,
        partialReason == null,
        partialReason,
        pageCount);
  }

  private Optional<QuestionMessage> mapAuthorized(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner raw,
      RetrievalRequest request,
      Bounds bounds,
      RawGuidTracker seenRawGuids) {
    if (raw == null
        || StringUtils.isBlank(raw.getGuid())
        || raw.getDateCreated() == null
        || !belongsToConversation(
            raw.getChats(), request.conversation().externalConversationId())) {
      return Optional.empty();
    }
    Instant timestamp = TimeSupport.epochSecondsOrMillisOrNow(raw.getDateCreated());
    if (!authorized(timestamp, request, bounds)) {
      return Optional.empty();
    }
    if (!seenRawGuids.accept(
        raw.getGuid(), raw.getHandle() == null ? null : raw.getHandle().getAddress())) {
      return Optional.empty();
    }
    return mapper.fromBlueBubbles(raw, request.accountId(), request.mappingSession());
  }

  private Optional<QuestionMessage> mapAuthorized(
      JournalMessage raw, RetrievalRequest request, Bounds bounds, RawGuidTracker seenRawGuids) {
    if (raw == null
        || StringUtils.isBlank(raw.messageGuid())
        || !authorized(raw.sourceTimestamp(), request, bounds)) {
      return Optional.empty();
    }
    if (!seenRawGuids.accept(raw.messageGuid(), raw.senderAccountId())) {
      return Optional.empty();
    }
    return mapper.fromJournal(raw, request.accountId(), request.mappingSession());
  }

  private boolean authorized(Instant timestamp, RetrievalRequest request, Bounds bounds) {
    return timestamp != null
        && !timestamp.isBefore(bounds.from())
        && timestamp.isBefore(bounds.to())
        && request.memberships().stream().anyMatch(interval -> interval.contains(timestamp));
  }

  private static boolean isBlueBubbles(RetrievalRequest request) {
    return IncomingMessage.TRANSPORT_BLUEBUBBLES.equalsIgnoreCase(
        request.conversation().transport());
  }

  private static boolean belongsToConversation(List<?> chats, String expectedChatGuid) {
    if (chats == null || StringUtils.isBlank(expectedChatGuid)) {
      return false;
    }
    return chats.stream()
        .filter(Objects::nonNull)
        .anyMatch(
            chat ->
                chat instanceof ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner value
                    && StringUtils.equals(value.getGuid(), expectedChatGuid));
  }

  private static List<Bounds> authorizedBounds(RetrievalRequest request, Bounds outer) {
    List<Bounds> clipped =
        request.memberships().stream()
            .filter(Objects::nonNull)
            .map(
                interval -> {
                  Instant from = max(outer.from(), interval.startedAt());
                  Instant to =
                      interval.endedAt() == null ? outer.to() : min(outer.to(), interval.endedAt());
                  return new Bounds(from, to);
                })
            .filter(bounds -> bounds.from().isBefore(bounds.to()))
            .sorted(Comparator.comparing(Bounds::from).thenComparing(Bounds::to))
            .toList();
    List<Bounds> merged = new ArrayList<>();
    for (Bounds next : clipped) {
      if (merged.isEmpty()) {
        merged.add(next);
        continue;
      }
      Bounds current = merged.getLast();
      if (next.from().isAfter(current.to())) {
        merged.add(next);
      } else {
        merged.set(merged.size() - 1, new Bounds(current.from(), max(current.to(), next.to())));
      }
    }
    return List.copyOf(merged);
  }

  private static List<QuestionMessage> sort(List<QuestionMessage> messages) {
    return messages.stream()
        .sorted(
            Comparator.comparing(QuestionMessage::timestamp)
                .thenComparing(QuestionMessage::messageGuid))
        .toList();
  }

  private static Instant max(Instant left, Instant right) {
    return left.isAfter(right) ? left : right;
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static String normalizeGuid(String guid) {
    return guid.trim().toLowerCase(Locale.ROOT);
  }

  private static int labelQuality(String participant) {
    if ("unknown participant".equals(participant)) {
      return 0;
    }
    if (participant.startsWith("participant ending ")) {
      return 1;
    }
    return 2;
  }

  private record Bounds(Instant from, Instant to) {}

  private static final class RawGuidTracker {
    private final Map<String, Integer> identityQualityByGuid = new LinkedHashMap<>();

    private boolean accept(String guid, @Nullable String identity) {
      String normalizedGuid = normalizeGuid(guid);
      int quality = StringUtils.isBlank(identity) ? 0 : 1;
      Integer priorQuality = identityQualityByGuid.get(normalizedGuid);
      if (priorQuality != null && priorQuality >= quality) {
        return false;
      }
      identityQualityByGuid.put(normalizedGuid, quality);
      return true;
    }
  }

  private final class CallBudget {
    private int pages;
    private @Nullable String partialReason;

    private @Nullable Duration reserve(Instant deadline) {
      Duration remaining = Duration.between(clock.instant(), deadline);
      if (remaining.isZero() || remaining.isNegative()) {
        limit(TIME_LIMIT);
        return null;
      }
      if (pages >= maxHistoryPages) {
        limit(HISTORY_LIMIT);
        return null;
      }
      pages++;
      return remaining;
    }

    private void limit(String reason) {
      if (partialReason == null || TIME_LIMIT.equals(reason)) {
        partialReason = reason;
      }
    }

    private int pages() {
      return pages;
    }

    private @Nullable String partialReason() {
      return partialReason;
    }
  }
}
