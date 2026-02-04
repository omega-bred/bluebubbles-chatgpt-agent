package io.breland.bbagent.server.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

public class ConversationState {
  private final Deque<ConversationTurn> history = new ArrayDeque<>();
  private final Map<String, ThreadContext> threadContexts = new ConcurrentHashMap<>();

  @Getter @Setter private String lastProcessedMessageGuid;
  @Getter @Setter private String lastProcessedMessageFingerprint;
  @Getter @Setter private String latestWorkflowRunId;

  public List<ConversationTurn> history() {
    return new ArrayList<>(history);
  }

  public void addTurn(ConversationTurn turn) {
    history.addLast(turn);
    while (history.size() > BBMessageAgent.MAX_HISTORY) {
      history.removeFirst();
    }
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

  public record ThreadContext(
      String threadRootGuid,
      String lastMessageGuid,
      String lastMessageText,
      String lastMessageSender,
      String lastMessageTimestamp,
      List<String> lastImageUrls) {}
}
