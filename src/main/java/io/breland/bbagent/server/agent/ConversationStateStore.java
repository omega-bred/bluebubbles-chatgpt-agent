package io.breland.bbagent.server.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public final class ConversationStateStore {
  private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

  public ConversationState get(String chatGuid) {
    return conversations.get(chatGuid);
  }

  public ConversationState computeIfAbsent(
      String chatGuid, Function<String, ConversationState> stateFactory) {
    return conversations.computeIfAbsent(chatGuid, stateFactory);
  }

  public Map<String, ConversationState> conversations() {
    return conversations;
  }
}
