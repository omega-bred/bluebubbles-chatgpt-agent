package io.breland.bbagent.server.agent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

public class ConversationState {
  private static final int MAX_RECENT_INCOMING_IDENTIFIERS = BBMessageAgent.MAX_HISTORY * 4;

  private final Deque<ConversationTurn> history = new ArrayDeque<>();
  private final SequencedSet<String> recentIncomingMessageGuids = new LinkedHashSet<>();
  private final SequencedSet<String> recentIncomingMessageFingerprints = new LinkedHashSet<>();
  private final SequencedSet<String> recordedIncomingMessageGuids = new LinkedHashSet<>();
  private final SequencedSet<String> recordedIncomingMessageFingerprints = new LinkedHashSet<>();
  private final SequencedSet<String> recordedAssistantDeliveries = new LinkedHashSet<>();
  private final Deque<PendingIncomingTurn> pendingIncomingTurns = new ArrayDeque<>();
  private final Set<String> pendingIncomingMessageGuids = new HashSet<>();
  private final Set<String> pendingIncomingMessageFingerprints = new HashSet<>();
  private final Map<String, ThreadContext> threadContexts = new ConcurrentHashMap<>();
  private final Map<String, PendingTermsAcceptance> pendingTermsAcceptances =
      new ConcurrentHashMap<>();

  @Getter @Setter private String lastProcessedMessageGuid;
  @Getter @Setter private String lastProcessedMessageFingerprint;
  @Getter @Setter private Instant latestProcessedMessageTimestamp;
  @Getter @Setter private String latestWorkflowRunId;
  @Getter @Setter private String latestWorkflowMessageGuid;
  @Getter @Setter private String latestReactionMessageGuid;
  @Getter @Setter private Long latestResponseVersion;
  @Getter @Setter private Long latestReactionVersion;
  @Getter @Setter private String activeTypingToken;

  record Snapshot(
      List<ConversationTurn> history,
      List<String> recentGuids,
      List<String> recentFingerprints,
      List<String> recordedGuids,
      List<String> recordedFingerprints,
      List<String> assistantDeliveries,
      List<PendingIncomingTurn> pending,
      Map<String, ThreadContext> threads,
      Map<String, PendingTermsAcceptance> terms,
      String lastProcessedMessageGuid,
      String lastProcessedMessageFingerprint,
      Instant latestProcessedMessageTimestamp,
      String latestWorkflowRunId,
      String latestWorkflowMessageGuid,
      String latestReactionMessageGuid,
      Long latestResponseVersion,
      Long latestReactionVersion,
      String activeTypingToken) {}

  synchronized Snapshot snapshot() {
    return new Snapshot(
        List.copyOf(history),
        List.copyOf(recentIncomingMessageGuids),
        List.copyOf(recentIncomingMessageFingerprints),
        List.copyOf(recordedIncomingMessageGuids),
        List.copyOf(recordedIncomingMessageFingerprints),
        List.copyOf(recordedAssistantDeliveries),
        List.copyOf(pendingIncomingTurns),
        Map.copyOf(threadContexts),
        Map.copyOf(pendingTermsAcceptances),
        lastProcessedMessageGuid,
        lastProcessedMessageFingerprint,
        latestProcessedMessageTimestamp,
        latestWorkflowRunId,
        latestWorkflowMessageGuid,
        latestReactionMessageGuid,
        latestResponseVersion,
        latestReactionVersion,
        activeTypingToken);
  }

  static ConversationState fromSnapshot(Snapshot snapshot) {
    ConversationState state = new ConversationState();
    state.history.addAll(snapshot.history());
    state.recentIncomingMessageGuids.addAll(snapshot.recentGuids());
    state.recentIncomingMessageFingerprints.addAll(snapshot.recentFingerprints());
    state.recordedIncomingMessageGuids.addAll(snapshot.recordedGuids());
    state.recordedIncomingMessageFingerprints.addAll(snapshot.recordedFingerprints());
    if (snapshot.assistantDeliveries() != null) {
      state.recordedAssistantDeliveries.addAll(snapshot.assistantDeliveries());
    }
    for (PendingIncomingTurn turn : snapshot.pending()) {
      state.pendingIncomingTurns.addLast(turn);
      if (turn.messageGuid() != null) state.pendingIncomingMessageGuids.add(turn.messageGuid());
      if (turn.fingerprint() != null)
        state.pendingIncomingMessageFingerprints.add(turn.fingerprint());
    }
    state.threadContexts.putAll(snapshot.threads());
    state.pendingTermsAcceptances.putAll(snapshot.terms());
    state.lastProcessedMessageGuid = snapshot.lastProcessedMessageGuid();
    state.lastProcessedMessageFingerprint = snapshot.lastProcessedMessageFingerprint();
    state.latestProcessedMessageTimestamp = snapshot.latestProcessedMessageTimestamp();
    state.latestWorkflowRunId = snapshot.latestWorkflowRunId();
    state.latestWorkflowMessageGuid = snapshot.latestWorkflowMessageGuid();
    state.latestReactionMessageGuid = snapshot.latestReactionMessageGuid();
    state.latestResponseVersion = snapshot.latestResponseVersion();
    state.latestReactionVersion = snapshot.latestReactionVersion();
    state.activeTypingToken = snapshot.activeTypingToken();
    return state;
  }

  public synchronized List<ConversationTurn> history() {
    return new ArrayList<>(history);
  }

  public synchronized void addTurn(ConversationTurn turn) {
    if (turn == null) {
      return;
    }
    history.addLast(turn);
    while (history.size() > BBMessageAgent.MAX_HISTORY) {
      history.removeFirst();
    }
  }

  boolean rememberAssistantDelivery(String deliveryId) {
    if (recordedAssistantDeliveries.contains(deliveryId)) return false;
    remember(recordedAssistantDeliveries, deliveryId);
    return true;
  }

  public synchronized boolean hasSeenIncomingMessage(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    String guid = normalize(message.messageGuid());
    if (guid != null && recentIncomingMessageGuids.contains(guid)) {
      return true;
    }
    String fingerprint = normalize(message.computeMessageFingerprint());
    return fingerprint != null && recentIncomingMessageFingerprints.contains(fingerprint);
  }

  public synchronized boolean isStaleIncomingMessage(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    Instant timestamp = message.timestamp();
    return timestamp != null
        && latestProcessedMessageTimestamp != null
        && timestamp.isBefore(latestProcessedMessageTimestamp);
  }

  public synchronized void markIncomingMessageSeen(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return;
    }
    String guid = normalize(message.messageGuid());
    String fingerprint = normalize(message.computeMessageFingerprint());
    remember(recentIncomingMessageGuids, guid);
    remember(recentIncomingMessageFingerprints, fingerprint);
    lastProcessedMessageGuid = guid;
    lastProcessedMessageFingerprint = fingerprint;
    if (message.timestamp() != null
        && (latestProcessedMessageTimestamp == null
            || message.timestamp().isAfter(latestProcessedMessageTimestamp))) {
      latestProcessedMessageTimestamp = message.timestamp();
    }
  }

  public synchronized void recordIncomingTurnIfAbsent(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return;
    }
    markIncomingMessageSeen(message);
    String guid = normalize(message.messageGuid());
    String fingerprint = normalize(message.computeMessageFingerprint());
    if ((guid != null && recordedIncomingMessageGuids.contains(guid))
        || (fingerprint != null && recordedIncomingMessageFingerprints.contains(fingerprint))) {
      return;
    }
    Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
    addTurn(ConversationTurn.user(message.summaryForHistory(), timestamp));
    remember(recordedIncomingMessageGuids, guid);
    remember(recordedIncomingMessageFingerprints, fingerprint);
  }

  public synchronized void recordPendingIncomingTurn(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return;
    }
    markIncomingMessageSeen(message);
    String guid = normalize(message.messageGuid());
    String fingerprint = normalize(message.computeMessageFingerprint());
    if ((guid != null && recordedIncomingMessageGuids.contains(guid))
        || (fingerprint != null && recordedIncomingMessageFingerprints.contains(fingerprint))
        || (guid != null && pendingIncomingMessageGuids.contains(guid))
        || (fingerprint != null && pendingIncomingMessageFingerprints.contains(fingerprint))) {
      return;
    }
    Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
    pendingIncomingTurns.addLast(
        new PendingIncomingTurn(
            guid, fingerprint, ConversationTurn.user(message.summaryForHistory(), timestamp)));
    if (guid != null) {
      pendingIncomingMessageGuids.add(guid);
    }
    if (fingerprint != null) {
      pendingIncomingMessageFingerprints.add(fingerprint);
    }
    while (pendingIncomingTurns.size() > BBMessageAgent.MAX_HISTORY) {
      PendingIncomingTurn removed = pendingIncomingTurns.removeFirst();
      if (removed.messageGuid() != null) {
        pendingIncomingMessageGuids.remove(removed.messageGuid());
      }
      if (removed.fingerprint() != null) {
        pendingIncomingMessageFingerprints.remove(removed.fingerprint());
      }
    }
  }

  public synchronized List<PendingIncomingTurn> pendingIncomingTurns() {
    return new ArrayList<>(pendingIncomingTurns);
  }

  public synchronized void recordPendingIncomingTurnsToHistory() {
    while (!pendingIncomingTurns.isEmpty()) {
      PendingIncomingTurn pending = pendingIncomingTurns.removeFirst();
      String guid = pending.messageGuid();
      String fingerprint = pending.fingerprint();
      if ((guid != null && recordedIncomingMessageGuids.contains(guid))
          || (fingerprint != null && recordedIncomingMessageFingerprints.contains(fingerprint))) {
        continue;
      }
      addTurn(pending.turn());
      remember(recordedIncomingMessageGuids, guid);
      remember(recordedIncomingMessageFingerprints, fingerprint);
    }
    pendingIncomingMessageGuids.clear();
    pendingIncomingMessageFingerprints.clear();
  }

  public void recordThreadMessage(String threadRootGuid, ThreadContext context) {
    if (threadRootGuid == null || threadRootGuid.isBlank() || context == null) {
      return;
    }
    threadContexts.put(threadRootGuid, context);
  }

  public ThreadContext getThreadContext(String threadRootGuid) {
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return null;
    }
    return threadContexts.get(threadRootGuid);
  }

  public synchronized PendingTermsAcceptance recordPendingTermsAcceptance(
      IncomingMessage originalMessage, String threadRootGuid) {
    if (originalMessage == null) {
      return null;
    }
    PendingTermsAcceptance pending =
        new PendingTermsAcceptance(
            originalMessage,
            normalize(threadRootGuid),
            normalize(originalMessage.sender()),
            Instant.now());
    pendingTermsAcceptances.put(
        termsAcceptanceKey(pending.sender(), pending.threadRootGuid()), pending);
    return pending;
  }

  public synchronized PendingTermsAcceptance getPendingTermsAcceptance(
      String sender, String threadRootGuid) {
    return pendingTermsAcceptances.get(
        termsAcceptanceKey(normalize(sender), normalize(threadRootGuid)));
  }

  public synchronized PendingTermsAcceptance getLatestPendingTermsAcceptance(String sender) {
    String cleanSender = normalize(sender);
    if (cleanSender == null) {
      return null;
    }
    String senderKey = cleanSender.toLowerCase(Locale.ROOT);
    PendingTermsAcceptance latest = null;
    for (PendingTermsAcceptance pending : pendingTermsAcceptances.values()) {
      String pendingSender = normalize(pending.sender());
      if (pendingSender == null || !senderKey.equals(pendingSender.toLowerCase(Locale.ROOT))) {
        continue;
      }
      if (latest == null || pending.createdAt().isAfter(latest.createdAt())) {
        latest = pending;
      }
    }
    return latest;
  }

  public synchronized void clearPendingTermsAcceptance(PendingTermsAcceptance pending) {
    if (pending == null) {
      return;
    }
    pendingTermsAcceptances.remove(
        termsAcceptanceKey(pending.sender(), pending.threadRootGuid()), pending);
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static String termsAcceptanceKey(String sender, String threadRootGuid) {
    String cleanSender = normalize(sender);
    String cleanThreadRootGuid = normalize(threadRootGuid);
    return (cleanSender == null ? "" : cleanSender.toLowerCase(Locale.ROOT))
        + "|"
        + (cleanThreadRootGuid == null ? "" : cleanThreadRootGuid);
  }

  private static void remember(SequencedSet<String> values, String value) {
    if (value == null || !values.add(value)) {
      return;
    }
    while (values.size() > MAX_RECENT_INCOMING_IDENTIFIERS) {
      values.removeFirst();
    }
  }

  public record ThreadContext(
      String threadRootGuid,
      String lastMessageGuid,
      String lastMessageText,
      String lastMessageSender,
      String lastMessageTimestamp,
      String lastImageMessageGuid,
      List<String> lastImageUrls) {}

  public record PendingIncomingTurn(String messageGuid, String fingerprint, ConversationTurn turn) {
    boolean matches(IncomingMessage message) {
      if (message == null) {
        return false;
      }
      String currentGuid = normalize(message.messageGuid());
      if (messageGuid != null && messageGuid.equals(currentGuid)) {
        return true;
      }
      String currentFingerprint = normalize(message.computeMessageFingerprint());
      return fingerprint != null && fingerprint.equals(currentFingerprint);
    }
  }

  public record PendingTermsAcceptance(
      IncomingMessage originalMessage, String threadRootGuid, String sender, Instant createdAt) {}
}
