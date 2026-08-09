package io.breland.bbagent.server.agent.memory;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.TimeSupport;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.CoverageStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalMode;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalRequest;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.SearchPlan;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationQuestionHistoryRetriever {
  private static final int MAX_CANDIDATE_MESSAGES = 5_000;
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
      @Value("${bbagent.memory.group.qa.max-search-terms:5}") int maxSearchTerms,
      @Value("${bbagent.memory.group.qa.search-page-size:500}") int pageSize,
      @Value("${bbagent.memory.group.qa.max-history-pages:100}") int maxHistoryPages,
      @Value("${bbagent.memory.group.qa.neighbor-message-count:3}") int neighborMessageCount,
      @Value("${bbagent.memory.group.qa.max-aggregate-characters:300000}")
          int maxAggregateCharacters) {
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
    if (maxHistoryPages <= 0) {
      throw new IllegalArgumentException("max history pages must be positive");
    }
    if (neighborMessageCount < 0) {
      throw new IllegalArgumentException("neighbor message count must not be negative");
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
    if (!bounds.from().isBefore(bounds.to())) {
      return completeResult(
          List.of(), RetrievalMode.EXACT_SEARCH, bounds.to(), 0, plan.senderHint());
    }

    CandidateAccumulator candidates = new CandidateAccumulator();
    CallBudget budget = new CallBudget();
    Set<String> contextualizedHits = new HashSet<>();
    boolean stop = false;
    for (String term : plan.terms()) {
      for (int offset = 0; !stop; offset += pageSize) {
        if (!budget.reserve(request.deadline())) {
          stop = true;
          break;
        }
        List<Message> page =
            Objects.requireNonNull(
                bb.searchConversationHistory(
                    request.conversation().externalConversationId(),
                    term,
                    bounds.from(),
                    bounds.to(),
                    pageSize,
                    offset),
                "history search returned no page");
        for (Message raw : page) {
          Optional<QuestionMessage> mapped = mapAuthorized(raw, request, bounds);
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
              && !addNeighbors(hit, request, bounds, candidates, budget)) {
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
    return result(
        candidates.values(), RetrievalMode.EXACT_SEARCH, bounds, budget, plan.senderHint());
  }

  public RetrievalResult retrieveChronological(RetrievalRequest request) {
    Objects.requireNonNull(request, "request");
    Bounds bounds = new Bounds(request.from(), request.to());
    CandidateAccumulator candidates = new CandidateAccumulator();
    CallBudget budget = new CallBudget();
    for (int offset = 0; ; offset += pageSize) {
      if (!budget.reserve(request.deadline())) {
        break;
      }
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> page;
      try {
        page =
            Objects.requireNonNull(
                bb.getMessagesInChat(
                    request.conversation().externalConversationId(),
                    bounds.from(),
                    bounds.to(),
                    offset,
                    pageSize,
                    "ASC"),
                "chronological history returned no page");
      } catch (RuntimeException sourceFailure) {
        return journalFallback(request, bounds, candidates, budget);
      }
      for (ApiV1ChatChatGuidMessageGet200ResponseDataInner raw : page) {
        Optional<QuestionMessage> mapped = mapAuthorized(raw, request, bounds);
        if (mapped.isPresent() && !candidates.add(mapped.get())) {
          budget.limit(HISTORY_LIMIT);
          break;
        }
      }
      if (budget.partialReason() != null || page.size() < pageSize) {
        break;
      }
    }
    return result(candidates.values(), RetrievalMode.CHRONOLOGICAL, bounds, budget, null);
  }

  private RetrievalResult journalFallback(
      RetrievalRequest request, Bounds bounds, CandidateAccumulator candidates, CallBudget budget) {
    if (deadlineReached(request.deadline())) {
      budget.limit(TIME_LIMIT);
      return result(candidates.values(), RetrievalMode.CHRONOLOGICAL, bounds, budget, null);
    }
    List<JournalMessage> journal =
        Objects.requireNonNull(
            store.findMessages(
                request.conversation().conversationId(), bounds.from(), bounds.to().minusNanos(1)),
            "journal returned no messages");
    Instant journalCoverageThrough = bounds.from();
    for (JournalMessage raw : journal) {
      Optional<QuestionMessage> mapped = mapAuthorized(raw, request, bounds);
      if (mapped.isPresent()) {
        journalCoverageThrough =
            journalCoverageThrough.isAfter(mapped.get().timestamp())
                ? journalCoverageThrough
                : mapped.get().timestamp();
        if (!candidates.add(mapped.get())) {
          break;
        }
      }
    }
    budget.limit(SOURCE_UNAVAILABLE);
    List<QuestionMessage> sorted = sort(candidates.values(), null);
    return new RetrievalResult(
        sorted,
        RetrievalMode.CHRONOLOGICAL,
        CoverageStatus.PARTIAL,
        journalCoverageThrough,
        SOURCE_UNAVAILABLE,
        budget.pages());
  }

  private boolean addNeighbors(
      QuestionMessage hit,
      RetrievalRequest request,
      Bounds bounds,
      CandidateAccumulator candidates,
      CallBudget budget) {
    if (neighborMessageCount == 0) {
      return true;
    }
    if (bounds.from().isBefore(hit.timestamp())) {
      if (!addNeighborPage(
          request, bounds, candidates, budget, bounds.from(), hit.timestamp(), "DESC")) {
        return false;
      }
    }
    if (hit.timestamp().isBefore(bounds.to())) {
      return addNeighborPage(
          request, bounds, candidates, budget, hit.timestamp(), bounds.to(), "ASC");
    }
    return true;
  }

  private boolean addNeighborPage(
      RetrievalRequest request,
      Bounds bounds,
      CandidateAccumulator candidates,
      CallBudget budget,
      Instant from,
      Instant to,
      String sort) {
    if (!budget.reserve(request.deadline())) {
      return false;
    }
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> neighbors =
        Objects.requireNonNull(
            bb.getMessagesInChat(
                request.conversation().externalConversationId(),
                from,
                to,
                0,
                neighborMessageCount + 1,
                sort),
            "neighbor history returned no page");
    for (ApiV1ChatChatGuidMessageGet200ResponseDataInner raw : neighbors) {
      Optional<QuestionMessage> mapped = mapAuthorized(raw, request, bounds);
      if (mapped.isPresent() && !candidates.add(mapped.get())) {
        budget.limit(HISTORY_LIMIT);
        return false;
      }
    }
    return true;
  }

  private Optional<QuestionMessage> mapAuthorized(
      Message raw, RetrievalRequest request, Bounds bounds) {
    if (raw == null || raw.getGuid() == null || raw.getDateCreated() == null) {
      return Optional.empty();
    }
    Instant timestamp = TimeSupport.epochSecondsOrMillisOrNow(raw.getDateCreated());
    if (!authorized(timestamp, request, bounds)) {
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
            .chats(
                List.of(
                    new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner()
                        .guid(request.conversation().externalConversationId())));
    return mapper.fromBlueBubbles(normalized, request.accountId());
  }

  private Optional<QuestionMessage> mapAuthorized(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner raw,
      RetrievalRequest request,
      Bounds bounds) {
    if (raw == null || raw.getDateCreated() == null) {
      return Optional.empty();
    }
    Instant timestamp = TimeSupport.epochSecondsOrMillisOrNow(raw.getDateCreated());
    if (!authorized(timestamp, request, bounds)) {
      return Optional.empty();
    }
    return mapper.fromBlueBubbles(raw, request.accountId());
  }

  private Optional<QuestionMessage> mapAuthorized(
      JournalMessage raw, RetrievalRequest request, Bounds bounds) {
    if (raw == null || !authorized(raw.sourceTimestamp(), request, bounds)) {
      return Optional.empty();
    }
    return mapper.fromJournal(raw, request.accountId());
  }

  private boolean authorized(Instant timestamp, RetrievalRequest request, Bounds bounds) {
    return timestamp != null
        && !timestamp.isBefore(bounds.from())
        && timestamp.isBefore(bounds.to())
        && request.memberships().stream().anyMatch(interval -> interval.contains(timestamp));
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

  private List<QuestionMessage> sort(List<QuestionMessage> messages, String senderHint) {
    String safeHint = StringUtils.trimToNull(senderHint);
    Comparator<QuestionMessage> comparator =
        Comparator.comparing(QuestionMessage::timestamp)
            .thenComparing(QuestionMessage::messageGuid);
    if (safeHint != null) {
      comparator =
          Comparator.comparingInt(
                  (QuestionMessage message) ->
                      message.participant().equalsIgnoreCase(safeHint) ? 0 : 1)
              .thenComparing(comparator);
    }
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

  private void validatePlan(SearchPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (plan.terms().size() > maxSearchTerms) {
      throw new IllegalArgumentException("search plan has too many terms");
    }
    if (plan.terms().stream().anyMatch(StringUtils::isBlank)) {
      throw new IllegalArgumentException("search plan terms must not be blank");
    }
  }

  private boolean deadlineReached(Instant deadline) {
    return !clock.instant().isBefore(deadline);
  }

  private record Bounds(Instant from, Instant to) {}

  private final class CallBudget {
    private int pages;
    private String partialReason;

    private boolean reserve(Instant deadline) {
      if (deadlineReached(deadline)) {
        limit(TIME_LIMIT);
        return false;
      }
      if (pages >= maxHistoryPages) {
        limit(HISTORY_LIMIT);
        return false;
      }
      pages++;
      return true;
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

    private int labelQuality(String participant) {
      if ("unknown participant".equals(participant)) {
        return 0;
      }
      if (participant.startsWith("participant ending ")) {
        return 1;
      }
      return 2;
    }

    private List<QuestionMessage> values() {
      return new ArrayList<>(messages.values());
    }
  }
}
