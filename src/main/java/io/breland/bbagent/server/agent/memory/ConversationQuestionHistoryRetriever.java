package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.TimeSupport;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.CoverageStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistorySource;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindow;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.HistoryWindowCursor;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalMode;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesHandleAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationQuestionHistoryRetriever {
  private static final int MAX_CANDIDATE_MESSAGES = 5_000;
  private static final int MAX_SOURCE_CALLS = 100;
  private static final String HISTORY_LIMIT = "history_limit";
  private static final String TIME_LIMIT = "time_limit";
  private static final String SOURCE_UNAVAILABLE = "source_unavailable";

  private final BBHttpClientWrapper bb;
  private final ConversationMemoryStore store;
  private final ConversationHistoryMessageMapper mapper;
  private final int maxSearchTerms;
  private final int pageSize;
  private final int maxHistoryPages;
  private final int neighborMessageCount;
  private final int maxAggregateCharacters;
  private final Clock clock;

  @Autowired
  public ConversationQuestionHistoryRetriever(
      BBHttpClientWrapper bb,
      ConversationMemoryStore store,
      ConversationHistoryMessageMapper mapper,
      @Value("${bbagent.memory.group.qa.max-search-terms}") int maxSearchTerms,
      @Value("${bbagent.memory.group.qa.search-page-size}") int pageSize,
      @Value("${bbagent.memory.group.qa.max-history-pages}") int maxHistoryPages,
      @Value("${bbagent.memory.group.qa.neighbor-message-count}") int neighborMessageCount,
      @Value("${bbagent.memory.group.qa.max-aggregate-characters}") int maxAggregateCharacters) {
    this(
        bb,
        store,
        mapper,
        maxSearchTerms,
        pageSize,
        maxHistoryPages,
        neighborMessageCount,
        maxAggregateCharacters,
        Clock.systemUTC());
  }

  ConversationQuestionHistoryRetriever(
      BBHttpClientWrapper bb,
      ConversationMemoryStore store,
      ConversationHistoryMessageMapper mapper,
      int maxSearchTerms,
      int pageSize,
      int maxHistoryPages,
      int neighborMessageCount,
      int maxAggregateCharacters,
      Clock clock) {
    this.bb = Objects.requireNonNull(bb, "bb");
    this.store = Objects.requireNonNull(store, "store");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    if (maxSearchTerms <= 0) {
      throw new IllegalArgumentException("max search terms must be positive");
    }
    if (pageSize <= 0 || pageSize > 500) {
      throw new IllegalArgumentException("history page size must be between 1 and 500");
    }
    if (maxHistoryPages <= 0 || maxHistoryPages > MAX_SOURCE_CALLS) {
      throw new IllegalArgumentException("max history pages must be between 1 and 100");
    }
    if (neighborMessageCount <= 0) {
      throw new IllegalArgumentException("neighbor message count must be positive");
    }
    if (maxAggregateCharacters <= 0) {
      throw new IllegalArgumentException("max aggregate characters must be positive");
    }
    this.maxSearchTerms = maxSearchTerms;
    this.pageSize = pageSize;
    this.maxHistoryPages = maxHistoryPages;
    this.neighborMessageCount = neighborMessageCount;
    this.maxAggregateCharacters = maxAggregateCharacters;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RetrievalResult retrieveExact(RetrievalRequest request, SearchPlan plan) {
    Objects.requireNonNull(request, "request");
    validatePlan(plan);
    Bounds bounds = exactBounds(request, plan);
    List<Bounds> sourceBounds = authorizedBounds(request, bounds);
    if (sourceBounds.isEmpty()) {
      return completeResult(
          List.of(), RetrievalMode.EXACT_SEARCH, bounds.to(), 0, plan.senderHint());
    }

    if (!isBlueBubbles(request)) {
      if (plan.terms().isEmpty()) {
        return partialResult(
            List.of(), RetrievalMode.EXACT_SEARCH, bounds.from(), SOURCE_UNAVAILABLE, 0, null);
      }
      return journalOnly(
          request,
          bounds,
          sourceBounds,
          RetrievalMode.EXACT_SEARCH,
          plan.terms(),
          plan.senderHint());
    }

    CandidateAccumulator candidates = new CandidateAccumulator();
    CallBudget budget = new CallBudget();
    Set<String> contextualizedHits = new HashSet<>();
    RawGuidTracker seenRawGuids = new RawGuidTracker();
    try {
      boolean stop = false;
      for (String term : plan.terms()) {
        for (Bounds sourceBound : sourceBounds) {
          for (int offset = 0; !stop; offset += pageSize) {
            Duration remaining = budget.reserve(request.deadline());
            if (remaining == null) {
              stop = true;
              break;
            }
            List<Message> page =
                Objects.requireNonNull(
                    bb.searchConversationHistoryForQuestion(
                        request.conversation().externalConversationId(),
                        term,
                        sourceBound.from(),
                        sourceBound.to(),
                        pageSize,
                        offset,
                        remaining),
                    "history search returned no page");
            for (Message raw : page) {
              Optional<QuestionMessage> mapped =
                  mapAuthorized(raw, request, sourceBound, seenRawGuids);
              if (mapped.isEmpty()) {
                continue;
              }
              QuestionMessage hit = mapped.get();
              if (!candidates.add(hit)) {
                budget.limit(HISTORY_LIMIT);
                stop = true;
                break;
              }
              if (contextualizedHits.add(hit.messageGuid())
                  && !addNeighbors(hit, request, sourceBound, candidates, budget, seenRawGuids)) {
                stop = true;
                break;
              }
            }
            if (page.size() < pageSize) {
              break;
            }
          }
          if (stop) {
            break;
          }
        }
        if (stop) {
          break;
        }
      }
    } catch (RuntimeException sourceFailure) {
      budget.limit(SOURCE_UNAVAILABLE);
      throw new PartialRetrievalException(
          result(
              candidates.values(), RetrievalMode.EXACT_SEARCH, bounds, budget, plan.senderHint()),
          sourceFailure);
    }
    return result(
        candidates.values(), RetrievalMode.EXACT_SEARCH, bounds, budget, plan.senderHint());
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

  private @Nullable String partialReason(
      CallBudget budget, @Nullable String inheritedPartialReason) {
    return budget.partialReason() == null ? inheritedPartialReason : budget.partialReason();
  }

  private void addWindowMessage(List<QuestionMessage> messages, QuestionMessage candidate) {
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

  private HistoryWindow historyWindow(
      List<QuestionMessage> messages,
      @Nullable HistoryWindowCursor nextCursor,
      boolean sourceExhausted,
      @Nullable String partialReason,
      int pageCount) {
    return new HistoryWindow(
        sort(messages, null),
        nextCursor,
        sourceExhausted,
        partialReason == null,
        partialReason,
        pageCount);
  }

  public RetrievalResult retrieveChronological(RetrievalRequest request) {
    Objects.requireNonNull(request, "request");
    Bounds bounds = new Bounds(request.from(), request.to());
    List<Bounds> sourceBounds = authorizedBounds(request, bounds);
    if (sourceBounds.isEmpty()) {
      return completeResult(List.of(), RetrievalMode.CHRONOLOGICAL, bounds.to(), 0, null);
    }
    if (!isBlueBubbles(request)) {
      return journalOnly(
          request, bounds, sourceBounds, RetrievalMode.CHRONOLOGICAL, List.of(), null);
    }
    CandidateAccumulator candidates = new CandidateAccumulator();
    CallBudget budget = new CallBudget();
    RawGuidTracker seenRawGuids = new RawGuidTracker();
    boolean stop = false;
    for (Bounds sourceBound : sourceBounds) {
      for (int offset = 0; !stop; offset += pageSize) {
        Duration remaining = budget.reserve(request.deadline());
        if (remaining == null) {
          stop = true;
          break;
        }
        List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> page;
        try {
          page =
              Objects.requireNonNull(
                  bb.getMessagesInChatForQuestion(
                      request.conversation().externalConversationId(),
                      sourceBound.from(),
                      sourceBound.to(),
                      offset,
                      pageSize,
                      "ASC",
                      remaining),
                  "chronological history returned no page");
        } catch (RuntimeException sourceFailure) {
          try {
            return journalFallback(request, bounds, sourceBounds, candidates, budget, seenRawGuids);
          } catch (RuntimeException journalFailure) {
            budget.limit(SOURCE_UNAVAILABLE);
            throw new PartialRetrievalException(
                result(candidates.values(), RetrievalMode.CHRONOLOGICAL, bounds, budget, null),
                journalFailure);
          }
        }
        try {
          for (ApiV1ChatChatGuidMessageGet200ResponseDataInner raw : page) {
            Optional<QuestionMessage> mapped =
                mapAuthorized(raw, request, sourceBound, seenRawGuids);
            if (mapped.isPresent() && !candidates.add(mapped.get())) {
              budget.limit(HISTORY_LIMIT);
              break;
            }
          }
        } catch (RuntimeException processingFailure) {
          budget.limit(SOURCE_UNAVAILABLE);
          throw new PartialRetrievalException(
              result(candidates.values(), RetrievalMode.CHRONOLOGICAL, bounds, budget, null),
              processingFailure);
        }
        if (budget.partialReason() != null) {
          stop = true;
          break;
        }
        if (page.size() < pageSize) {
          break;
        }
      }
    }
    return result(candidates.values(), RetrievalMode.CHRONOLOGICAL, bounds, budget, null);
  }

  private RetrievalResult journalFallback(
      RetrievalRequest request,
      Bounds bounds,
      List<Bounds> sourceBounds,
      CandidateAccumulator candidates,
      CallBudget budget,
      RawGuidTracker seenRawGuids) {
    Instant journalCoverageThrough =
        pageJournal(
            request, sourceBounds, candidates, budget, seenRawGuids, List.of(), bounds.from());
    budget.limit(SOURCE_UNAVAILABLE);
    List<QuestionMessage> sorted = sort(candidates.values(), null);
    return new RetrievalResult(
        sorted,
        RetrievalMode.CHRONOLOGICAL,
        CoverageStatus.PARTIAL,
        journalCoverageThrough,
        budget.partialReason(),
        budget.pages());
  }

  private RetrievalResult journalOnly(
      RetrievalRequest request,
      Bounds bounds,
      List<Bounds> sourceBounds,
      RetrievalMode mode,
      List<String> requiredTerms,
      String senderHint) {
    CandidateAccumulator candidates = new CandidateAccumulator();
    CallBudget budget = new CallBudget();
    RawGuidTracker seenRawGuids = new RawGuidTracker();
    Instant coverageThrough;
    try {
      coverageThrough =
          pageJournal(
              request,
              sourceBounds,
              candidates,
              budget,
              seenRawGuids,
              requiredTerms,
              bounds.from());
    } catch (RuntimeException sourceFailure) {
      budget.limit(SOURCE_UNAVAILABLE);
      throw new PartialRetrievalException(
          result(candidates.values(), mode, bounds, budget, senderHint), sourceFailure);
    }
    budget.limit(SOURCE_UNAVAILABLE);
    return partialResult(
        sort(candidates.values(), senderHint),
        mode,
        coverageThrough,
        budget.partialReason(),
        budget.pages(),
        senderHint);
  }

  private Instant pageJournal(
      RetrievalRequest request,
      List<Bounds> sourceBounds,
      CandidateAccumulator candidates,
      CallBudget budget,
      RawGuidTracker seenRawGuids,
      List<String> requiredTerms,
      Instant initialCoverageThrough) {
    Instant coverageThrough = initialCoverageThrough;
    journalBounds:
    for (Bounds sourceBound : sourceBounds) {
      Instant afterTimestamp = null;
      String afterMessageGuid = null;
      while (true) {
        Duration remaining = budget.reserve(request.deadline());
        if (remaining == null) {
          break journalBounds;
        }
        List<JournalMessage> page =
            Objects.requireNonNull(
                store.findMessagePage(
                    request.conversation().conversationId(),
                    sourceBound.from(),
                    sourceBound.to(),
                    afterTimestamp,
                    afterMessageGuid,
                    pageSize,
                    remaining),
                "journal returned no page");
        for (JournalMessage raw : page) {
          if (raw == null
              || (!requiredTerms.isEmpty()
                  && requiredTerms.stream()
                      .noneMatch(term -> StringUtils.containsIgnoreCase(raw.text(), term)))) {
            continue;
          }
          Optional<QuestionMessage> mapped = mapAuthorized(raw, request, sourceBound, seenRawGuids);
          if (mapped.isEmpty()) {
            continue;
          }
          coverageThrough = max(coverageThrough, mapped.get().timestamp());
          if (!candidates.add(mapped.get())) {
            budget.limit(HISTORY_LIMIT);
            break journalBounds;
          }
        }
        if (page.size() < pageSize) {
          break;
        }
        JournalMessage cursor = page.getLast();
        if (cursor == null
            || cursor.sourceTimestamp() == null
            || StringUtils.isBlank(cursor.messageGuid())) {
          throw new IllegalStateException("journal returned an invalid page cursor");
        }
        afterTimestamp = cursor.sourceTimestamp();
        afterMessageGuid = cursor.messageGuid();
      }
    }
    return coverageThrough;
  }

  private boolean addNeighbors(
      QuestionMessage hit,
      RetrievalRequest request,
      Bounds bounds,
      CandidateAccumulator candidates,
      CallBudget budget,
      RawGuidTracker seenRawGuids) {
    if (bounds.from().isBefore(hit.timestamp())) {
      if (!addNeighborPage(
          request,
          bounds,
          candidates,
          budget,
          seenRawGuids,
          bounds.from(),
          hit.timestamp(),
          "DESC")) {
        return false;
      }
    }
    if (hit.timestamp().isBefore(bounds.to())) {
      return addNeighborPage(
          request, bounds, candidates, budget, seenRawGuids, hit.timestamp(), bounds.to(), "ASC");
    }
    return true;
  }

  private boolean addNeighborPage(
      RetrievalRequest request,
      Bounds bounds,
      CandidateAccumulator candidates,
      CallBudget budget,
      RawGuidTracker seenRawGuids,
      Instant from,
      Instant to,
      String sort) {
    Duration remaining = budget.reserve(request.deadline());
    if (remaining == null) {
      return false;
    }
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> neighbors =
        Objects.requireNonNull(
            bb.getMessagesInChatForQuestion(
                request.conversation().externalConversationId(),
                from,
                to,
                0,
                neighborMessageCount + 1,
                sort,
                remaining),
            "neighbor history returned no page");
    for (ApiV1ChatChatGuidMessageGet200ResponseDataInner raw : neighbors) {
      Optional<QuestionMessage> mapped = mapAuthorized(raw, request, bounds, seenRawGuids);
      if (mapped.isPresent() && !candidates.add(mapped.get())) {
        budget.limit(HISTORY_LIMIT);
        return false;
      }
    }
    return true;
  }

  private Optional<QuestionMessage> mapAuthorized(
      Message raw, RetrievalRequest request, Bounds bounds, RawGuidTracker seenRawGuids) {
    if (raw == null
        || raw.getGuid() == null
        || raw.getDateCreated() == null
        || !belongsToConversation(
            raw.getChats(), request.conversation().externalConversationId())) {
      return Optional.empty();
    }
    Instant timestamp = TimeSupport.epochSecondsOrMillisOrNow(raw.getDateCreated());
    if (!authorized(timestamp, request, bounds)) {
      return Optional.empty();
    }
    String sender = BlueBubblesHandleAddress.from(raw.getHandle());
    if (!seenRawGuids.accept(raw.getGuid().toString(), sender)) {
      return Optional.empty();
    }
    ApiV1ChatChatGuidMessageGet200ResponseDataInner normalized =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
            .guid(raw.getGuid().toString())
            .text(raw.getText())
            .dateCreated(raw.getDateCreated())
            .isFromMe(raw.getIsFromMe())
            .isSystemMessage(raw.getIsSystemMessage())
            .isServiceMessage(raw.getIsServiceMessage())
            .handle(
                sender == null
                    ? null
                    : new ApiV1ChatChatGuidMessageGet200ResponseDataInnerHandle().address(sender))
            .chats(
                raw.getChats().stream()
                    .map(
                        chat ->
                            new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner()
                                .guid(chat.getGuid()))
                    .toList());
    return mapper.fromBlueBubbles(normalized, request.accountId(), request.mappingSession());
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

  private static String normalizeGuid(String guid) {
    return guid.trim().toLowerCase(Locale.ROOT);
  }

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
            chat -> {
              if (chat instanceof io.breland.bbagent.generated.bluebubblesclient.model.Chat value) {
                return StringUtils.equals(value.getGuid(), expectedChatGuid);
              }
              if (chat instanceof ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner value) {
                return StringUtils.equals(value.getGuid(), expectedChatGuid);
              }
              return false;
            });
  }

  private static Instant max(Instant left, Instant right) {
    return left.isAfter(right) ? left : right;
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private RetrievalResult result(
      List<QuestionMessage> candidates,
      RetrievalMode mode,
      Bounds bounds,
      CallBudget budget,
      String senderHint) {
    List<QuestionMessage> sorted = sort(candidates, senderHint);
    if (budget.partialReason() == null) {
      return completeResult(sorted, mode, bounds.to(), budget.pages(), senderHint);
    }
    Instant coverageThrough =
        sorted.stream()
            .map(QuestionMessage::timestamp)
            .max(Comparator.naturalOrder())
            .orElse(bounds.from());
    return new RetrievalResult(
        sorted,
        mode,
        CoverageStatus.PARTIAL,
        coverageThrough,
        budget.partialReason(),
        budget.pages());
  }

  private RetrievalResult completeResult(
      List<QuestionMessage> candidates,
      RetrievalMode mode,
      Instant coverageThrough,
      int pages,
      String senderHint) {
    return new RetrievalResult(
        sort(candidates, senderHint), mode, CoverageStatus.COMPLETE, coverageThrough, null, pages);
  }

  private RetrievalResult partialResult(
      List<QuestionMessage> candidates,
      RetrievalMode mode,
      Instant coverageThrough,
      String reason,
      int pages,
      String senderHint) {
    return new RetrievalResult(
        sort(candidates, senderHint), mode, CoverageStatus.PARTIAL, coverageThrough, reason, pages);
  }

  private List<QuestionMessage> sort(List<QuestionMessage> messages, String senderHint) {
    String safeHint = StringUtils.trimToNull(senderHint);
    Comparator<QuestionMessage> comparator = Comparator.comparing(QuestionMessage::timestamp);
    if (safeHint != null) {
      comparator =
          comparator.thenComparingInt(
              message -> message.participant().equalsIgnoreCase(safeHint) ? 0 : 1);
    }
    comparator = comparator.thenComparing(QuestionMessage::messageGuid);
    return messages.stream().sorted(comparator).toList();
  }

  private Bounds exactBounds(RetrievalRequest request, SearchPlan plan) {
    Instant from = request.from();
    if (plan.fromHint() != null && plan.fromHint().isAfter(from)) {
      from = plan.fromHint();
    }
    Instant to = request.to();
    if (plan.toHint() != null && plan.toHint().isBefore(to)) {
      to = plan.toHint();
    }
    return new Bounds(from, to);
  }

  private List<Bounds> authorizedBounds(RetrievalRequest request, Bounds outer) {
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

  private void validatePlan(SearchPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (plan.terms().size() > maxSearchTerms) {
      throw new IllegalArgumentException("search plan has too many terms");
    }
    if (plan.terms().stream().anyMatch(StringUtils::isBlank)) {
      throw new IllegalArgumentException("search plan terms must not be blank");
    }
  }

  private record Bounds(Instant from, Instant to) {}

  static final class PartialRetrievalException extends RuntimeException {
    private final RetrievalResult partialResult;

    PartialRetrievalException(RetrievalResult partialResult, RuntimeException cause) {
      super("question history retrieval failed", cause);
      this.partialResult = Objects.requireNonNull(partialResult, "partialResult");
    }

    RetrievalResult partialResult() {
      return partialResult;
    }
  }

  private final class CallBudget {
    private int pages;
    private String partialReason;

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

    private String partialReason() {
      return partialReason;
    }
  }

  private final class CandidateAccumulator {
    private final LinkedHashMap<String, QuestionMessage> messages = new LinkedHashMap<>();
    private int characters;

    private boolean add(QuestionMessage message) {
      QuestionMessage existing = messages.get(message.messageGuid());
      if (existing != null) {
        if (labelQuality(message.participant()) > labelQuality(existing.participant())) {
          messages.put(message.messageGuid(), message);
        }
        return true;
      }
      if (messages.size() >= MAX_CANDIDATE_MESSAGES
          || characters + message.text().length() > maxAggregateCharacters) {
        return false;
      }
      messages.put(message.messageGuid(), message);
      characters += message.text().length();
      return true;
    }

    private List<QuestionMessage> values() {
      return new ArrayList<>(messages.values());
    }
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
}
