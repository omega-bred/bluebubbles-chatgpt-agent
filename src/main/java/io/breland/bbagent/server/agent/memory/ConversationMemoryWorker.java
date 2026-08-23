package io.breland.bbagent.server.agent.memory;

import static io.breland.bbagent.server.agent.memory.ConversationMemoryModels.corpusHash;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionBatch;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExtractionCheckpoint;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ModelExtraction;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.WorkClaim;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryWorker {
  private static final int CLAIM_LIMIT = 10;
  private static final int MAX_BATCH_MESSAGES = 200;
  private static final int MAX_BATCH_CHARACTERS = 40_000;
  private static final int MAX_OVERLAP_MESSAGES = 50;
  private static final Duration CONTEXT_OVERLAP = Duration.ofMinutes(10);

  private final ConversationMemoryStore store;
  private final ConversationMembershipService membershipService;
  private final ConversationMemoryModelClient modelClient;
  private final @Nullable OperationalMetricsService metrics;
  private final Clock clock;
  private final String workerId;
  private final boolean globallyEnabled;

  @Autowired
  public ConversationMemoryWorker(
      ConversationMemoryStore store,
      ConversationMembershipService membershipService,
      ConversationMemoryModelClient modelClient,
      @Nullable OperationalMetricsService metrics,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this(
        store,
        membershipService,
        modelClient,
        metrics,
        Clock.systemUTC(),
        UUID.randomUUID().toString(),
        globallyEnabled);
  }

  ConversationMemoryWorker(
      ConversationMemoryStore store,
      ConversationMembershipService membershipService,
      ConversationMemoryModelClient modelClient,
      @Nullable OperationalMetricsService metrics,
      Clock clock,
      String workerId) {
    this(store, membershipService, modelClient, metrics, clock, workerId, true);
  }

  ConversationMemoryWorker(
      ConversationMemoryStore store,
      ConversationMembershipService membershipService,
      ConversationMemoryModelClient modelClient,
      @Nullable OperationalMetricsService metrics,
      Clock clock,
      String workerId,
      boolean globallyEnabled) {
    this.store = store;
    this.membershipService = membershipService;
    this.modelClient = modelClient;
    this.metrics = metrics;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.workerId = workerId;
    this.globallyEnabled = globallyEnabled;
  }

  @Scheduled(
      fixedDelayString = "${bbagent.memory.group.worker-poll-interval:PT5S}",
      initialDelayString = "${bbagent.memory.group.worker-initial-delay:PT15S}")
  public void processDueConversationMemory() {
    if (!globallyEnabled) {
      return;
    }
    Instant now = clock.instant();
    for (WorkClaim claim : store.claimDueExtractionWork(workerId, now, CLAIM_LIMIT)) {
      process(claim);
    }
  }

  private void process(WorkClaim claim) {
    Instant startedAt = clock.instant();
    try {
      recordWorkLag(claim.conversationId(), startedAt);
      Optional<ConversationRecord> conversationValue =
          store.findConversation(claim.conversationId());
      if (conversationValue.isEmpty()
          || !conversationValue.get().group()
          || conversationValue.get().memoryEnabledAt() == null) {
        store.completeUnchangedExtraction(claim, startedAt);
        return;
      }
      ConversationRecord conversation = conversationValue.get();
      membershipService.refreshGroupMembership(claim.conversationId());

      Optional<ExtractionCheckpoint> checkpoint = store.findCheckpoint(claim.conversationId());
      Instant fromInclusive = conversation.memoryEnabledAt();
      if (checkpoint.isPresent() && checkpoint.get().lastProcessedAt() != null) {
        Instant overlapStart = checkpoint.get().lastProcessedAt().minus(CONTEXT_OVERLAP);
        if (overlapStart.isAfter(fromInclusive)) {
          fromInclusive = overlapStart;
        }
      }
      List<JournalMessage> available =
          store.findMessages(claim.conversationId(), fromInclusive, startedAt);
      SelectedBatch selected = selectBatch(available, checkpoint.orElse(null));
      if (selected.messages().isEmpty()) {
        store.completeUnchangedExtraction(claim, startedAt);
        return;
      }

      String corpusHash = corpusHash(selected.messages());
      if (checkpoint.isPresent()
          && StringUtils.equals(checkpoint.get().lastCorpusHash(), corpusHash)
          && !selected.hasMoreNewMessages()) {
        store.completeUnchangedExtraction(claim, startedAt);
        return;
      }

      ModelExtraction extraction =
          modelClient.extract(
              selected.messages(), store.findActiveArtifacts(claim.conversationId()));
      Instant processedAt = clock.instant();
      store.saveExtraction(
          claim,
          new ExtractionBatch(
              claim.conversationId(),
              selected.messages(),
              extraction.candidates(),
              extraction.summary(),
              extraction.itemPayload(),
              corpusHash,
              processedAt));
      if (selected.hasMoreNewMessages()) {
        store.scheduleExtraction(claim.conversationId(), processedAt);
      }
      recordExtraction(true, null, Duration.between(startedAt, clock.instant()));
    } catch (ConversationMembershipService.MembershipRefreshException e) {
      store.failExtractionWork(claim, startedAt, "membership_refresh");
      recordExtraction(false, "membership_refresh", Duration.between(startedAt, clock.instant()));
    } catch (RuntimeException e) {
      String failureType = OperationalMetricsService.failureType(e);
      store.failExtractionWork(claim, startedAt, failureType);
      recordExtraction(false, failureType, Duration.between(startedAt, clock.instant()));
    }
  }

  private void recordWorkLag(String conversationId, Instant now) {
    if (metrics == null) {
      return;
    }
    store
        .extractionAvailableAt(conversationId)
        .ifPresent(availableAt -> metrics.recordMemoryWorkLag(Duration.between(availableAt, now)));
  }

  private void recordExtraction(boolean success, @Nullable String failureType, Duration duration) {
    if (metrics != null) {
      metrics.recordMemoryExtraction(success, failureType, duration);
    }
  }

  private static SelectedBatch selectBatch(
      List<JournalMessage> messages, @Nullable ExtractionCheckpoint checkpoint) {
    List<JournalMessage> ordered =
        messages.stream()
            .sorted(
                Comparator.comparing(JournalMessage::sourceTimestamp)
                    .thenComparing(JournalMessage::messageGuid))
            .toList();
    List<JournalMessage> overlap = new ArrayList<>();
    List<JournalMessage> newMessages = new ArrayList<>();
    for (JournalMessage message : ordered) {
      if (isAfterCheckpoint(message, checkpoint)) {
        newMessages.add(message);
      } else {
        overlap.add(message);
      }
    }

    List<JournalMessage> selectedNew = new ArrayList<>();
    int selectedCharacters = 0;
    for (JournalMessage message : newMessages) {
      if (selectedNew.size() >= MAX_BATCH_MESSAGES) {
        break;
      }
      int remainingCharacters = MAX_BATCH_CHARACTERS - selectedCharacters;
      if (remainingCharacters <= 0) {
        break;
      }
      JournalMessage bounded = boundedMessage(message, remainingCharacters);
      selectedNew.add(bounded);
      selectedCharacters += textLength(bounded);
    }

    int remainingMessages = MAX_BATCH_MESSAGES - selectedNew.size();
    int remainingCharacters = MAX_BATCH_CHARACTERS - selectedCharacters;
    int overlapStart =
        Math.max(0, overlap.size() - Math.min(MAX_OVERLAP_MESSAGES, remainingMessages));
    List<JournalMessage> selectedOverlap = new ArrayList<>();
    for (int index = overlap.size() - 1; index >= overlapStart; index--) {
      JournalMessage message = overlap.get(index);
      int length = textLength(message);
      if (length <= remainingCharacters) {
        selectedOverlap.addFirst(message);
        remainingCharacters -= length;
      }
    }

    List<JournalMessage> selected = new ArrayList<>(selectedOverlap);
    selected.addAll(selectedNew);
    return new SelectedBatch(List.copyOf(selected), selectedNew.size() < newMessages.size());
  }

  private static boolean isAfterCheckpoint(
      JournalMessage message, @Nullable ExtractionCheckpoint checkpoint) {
    if (checkpoint == null || checkpoint.lastProcessedAt() == null) {
      return true;
    }
    int timestampComparison = message.sourceTimestamp().compareTo(checkpoint.lastProcessedAt());
    if (timestampComparison != 0) {
      return timestampComparison > 0;
    }
    return checkpoint.lastProcessedMessageGuid() == null
        || message.messageGuid().compareTo(checkpoint.lastProcessedMessageGuid()) > 0;
  }

  private static JournalMessage boundedMessage(JournalMessage message, int characterLimit) {
    String text = StringUtils.defaultString(message.text());
    if (text.length() <= characterLimit) {
      return message;
    }
    return new JournalMessage(
        message.messageGuid(),
        message.conversationId(),
        message.senderAccountId(),
        text.substring(0, characterLimit),
        message.sourceTimestamp(),
        message.fromAgent(),
        message.systemMessage(),
        message.contentHash());
  }

  private static int textLength(JournalMessage message) {
    return StringUtils.length(message.text());
  }

  private record SelectedBatch(List<JournalMessage> messages, boolean hasMoreNewMessages) {}
}
