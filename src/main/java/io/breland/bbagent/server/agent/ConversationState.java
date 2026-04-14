package io.breland.bbagent.server.agent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

public class ConversationState {
  private static final int MAX_RECENT_INCOMING_IDENTIFIERS = BBMessageAgent.MAX_HISTORY * 4;

  private final Deque<ConversationTurn> history = new ArrayDeque<>();
  private final Deque<String> recentIncomingMessageGuidOrder = new ArrayDeque<>();
  private final Set<String> recentIncomingMessageGuids = new HashSet<>();
  private final Deque<String> recentIncomingMessageFingerprintOrder = new ArrayDeque<>();
  private final Set<String> recentIncomingMessageFingerprints = new HashSet<>();
  private final Deque<String> recordedIncomingMessageGuidOrder = new ArrayDeque<>();
  private final Set<String> recordedIncomingMessageGuids = new HashSet<>();
  private final Deque<String> recordedIncomingMessageFingerprintOrder = new ArrayDeque<>();
  private final Set<String> recordedIncomingMessageFingerprints = new HashSet<>();
  private final Map<String, ThreadContext> threadContexts = new ConcurrentHashMap<>();

  @Getter @Setter private String lastProcessedMessageGuid;
  @Getter @Setter private String lastProcessedMessageFingerprint;
  @Getter @Setter private Instant latestProcessedMessageTimestamp;
  @Getter @Setter private String latestWorkflowRunId;

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
    remember(recentIncomingMessageGuidOrder, recentIncomingMessageGuids, guid);
    remember(recentIncomingMessageFingerprintOrder, recentIncomingMessageFingerprints, fingerprint);
    lastProcessedMessageGuid = guid;
    lastProcessedMessageFingerprint = fingerprint;
    if (message.timestamp() != null
        && (latestProcessedMessageTimestamp == null
            || message.timestamp().isAfter(latestProcessedMessageTimestamp))) {
      latestProcessedMessageTimestamp = message.timestamp();
    }
  }

  public synchronized boolean recordIncomingTurnIfAbsent(IncomingMessage message) {
    if (message == null || Boolean.TRUE.equals(message.fromMe())) {
      return false;
    }
    markIncomingMessageSeen(message);
    String guid = normalize(message.messageGuid());
    String fingerprint = normalize(message.computeMessageFingerprint());
    if ((guid != null && recordedIncomingMessageGuids.contains(guid))
        || (fingerprint != null && recordedIncomingMessageFingerprints.contains(fingerprint))) {
      return false;
    }
    Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
    addTurn(ConversationTurn.user(message.summaryForHistory(), timestamp));
    remember(recordedIncomingMessageGuidOrder, recordedIncomingMessageGuids, guid);
    remember(
        recordedIncomingMessageFingerprintOrder, recordedIncomingMessageFingerprints, fingerprint);
    return true;
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

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static void remember(Deque<String> order, Set<String> values, String value) {
    if (value == null || values.contains(value)) {
      return;
    }
    order.addLast(value);
    values.add(value);
    while (order.size() > MAX_RECENT_INCOMING_IDENTIFIERS) {
      String removed = order.removeFirst();
      values.remove(removed);
    }
  }

  public record ThreadContext(
      String threadRootGuid,
      String lastMessageGuid,
      String lastMessageText,
      String lastMessageSender,
      String lastMessageTimestamp,
      List<String> lastImageUrls) {}
}
