package io.breland.bbagent.server.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

public class ConversationState {
  private final Deque<ConversationTurn> history = new ArrayDeque<>();

  @Getter @Setter private String lastProcessedMessageGuid;
  @Getter @Setter private String lastProcessedMessageFingerprint;

  List<ConversationTurn> history() {
    return new ArrayList<>(history);
  }

  public void addTurn(ConversationTurn turn) {
    history.addLast(turn);
    while (history.size() > BBMessageAgent.MAX_HISTORY) {
      history.removeFirst();
    }
  }
}
