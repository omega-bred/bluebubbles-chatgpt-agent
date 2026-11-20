package io.breland.bbagent.server.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class ConversationStateStore {
  private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
  private final JdbcTemplate jdbc;
  private final AgentStateCodec codec;
  private final TransactionTemplate transaction;

  public ConversationStateStore() {
    jdbc = null;
    codec = null;
    transaction = null;
  }

  @Autowired
  ConversationStateStore(JdbcTemplate jdbc, AgentStateCodec codec, PlatformTransactionManager tx) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.transaction = new TransactionTemplate(tx);
  }

  public ConversationState get(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) return null;
    return jdbc == null ? conversations.get(chatGuid) : read(chatGuid, false);
  }

  ConversationState lockForDelivery(String chatGuid) {
    return jdbc == null ? get(chatGuid) : read(chatGuid, true);
  }

  public ConversationState computeIfAbsent(
      String chatGuid, Function<String, ConversationState> stateFactory) {
    ConversationState state = get(chatGuid);
    if (state != null) return state;
    return update(chatGuid, stateFactory, Function.identity());
  }

  public <T> T update(
      String chatGuid,
      Function<String, ConversationState> stateFactory,
      Function<ConversationState, T> work) {
    if (jdbc == null) {
      ConversationState state = conversations.computeIfAbsent(chatGuid, stateFactory);
      synchronized (state) {
        return work.apply(state);
      }
    }
    return transaction.execute(
        status -> {
          ConversationState state = read(chatGuid, true);
          if (state == null) {
            ConversationState initialState = stateFactory.apply(chatGuid);
            jdbc.update(
                """
                INSERT INTO agent_conversation_state (chat_guid, state_payload, updated_at)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """,
                chatGuid,
                codec.encode(initialState.snapshot()),
                java.sql.Timestamp.from(Instant.now()));
            state = read(chatGuid, true);
          }
          T result = work.apply(state);
          jdbc.update(
              "UPDATE agent_conversation_state SET state_payload = ?, updated_at = ? WHERE chat_guid = ?",
              codec.encode(state.snapshot()),
              java.sql.Timestamp.from(Instant.now()),
              chatGuid);
          return result;
        });
  }

  public void updateIfPresent(String chatGuid, Consumer<ConversationState> work) {
    if (get(chatGuid) != null) {
      update(
          chatGuid,
          ignored -> new ConversationState(),
          state -> {
            work.accept(state);
            return null;
          });
    }
  }

  private ConversationState read(String chatGuid, boolean lock) {
    String select = "SELECT state_payload FROM agent_conversation_state WHERE chat_guid = ?";
    List<String> rows =
        jdbc.queryForList(lock ? select + " FOR UPDATE" : select, String.class, chatGuid);
    return rows.isEmpty()
        ? null
        : ConversationState.fromSnapshot(
            codec.decode(rows.getFirst(), ConversationState.Snapshot.class));
  }

  public Map<String, ConversationState> conversations() {
    return conversations;
  }
}
