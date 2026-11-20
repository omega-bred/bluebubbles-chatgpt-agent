package io.breland.bbagent.server.agent.cadence;

import io.breland.bbagent.server.agent.AgentStateCodec;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class InboundMessageInbox {
  private static final Duration LEASE = Duration.ofMinutes(2);
  private final JdbcTemplate jdbc;
  private final AgentStateCodec codec;
  private final TransactionTemplate transaction;

  public InboundMessageInbox(
      JdbcTemplate jdbc, AgentStateCodec codec, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.transaction = new TransactionTemplate(manager);
  }

  public void accept(IncomingMessage message) {
    String guid = message.messageGuid();
    String key =
        DigestUtils.sha256Hex(
            codec.encode(
                List.of(
                    message.transportOrDefault(),
                    message.chatGuid(),
                    guid == null || guid.isBlank()
                        ? message.computeMessageFingerprint()
                        : "guid:" + guid)));
    transaction.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO agent_inbound_chat_order (chat_guid) VALUES (?) ON CONFLICT DO NOTHING",
              message.chatGuid());
          jdbc.queryForObject(
              "SELECT chat_guid FROM agent_inbound_chat_order WHERE chat_guid = ? FOR UPDATE",
              String.class,
              message.chatGuid());
          jdbc.update(
              """
          INSERT INTO agent_inbound_messages (message_key, chat_guid, message_payload, available_at)
          VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
          """,
              key,
              message.chatGuid(),
              codec.encode(message),
              at(Instant.now()));
        });
  }

  Claim claimNext(Instant now) {
    var candidates =
        jdbc.query(
            """
        SELECT item.inbox_id, item.attempts
          FROM agent_inbound_messages item
         WHERE item.completed_at IS NULL AND item.available_at <= ?
           AND (item.claimed_until IS NULL OR item.claimed_until < ?)
           AND NOT EXISTS (
             SELECT 1 FROM agent_inbound_messages earlier
              WHERE earlier.chat_guid = item.chat_guid AND earlier.completed_at IS NULL
                AND earlier.inbox_id < item.inbox_id
           )
         ORDER BY item.inbox_id LIMIT 8
        """,
            (row, index) ->
                new Claim(row.getLong(1), UUID.randomUUID().toString(), row.getInt(2) + 1),
            at(now),
            at(now));
    for (Claim claim : candidates) {
      int updated =
          jdbc.update(
              """
          UPDATE agent_inbound_messages SET claim_token = ?, claimed_until = ?, attempts = attempts + 1
           WHERE inbox_id = ? AND completed_at IS NULL AND available_at <= ?
             AND (claimed_until IS NULL OR claimed_until < ?)
          """,
              claim.token(),
              at(now.plus(LEASE)),
              claim.id(),
              at(now),
              at(now));
      if (updated == 1) return claim;
    }
    return null;
  }

  void prepare(
      Claim claim, BiFunction<IncomingMessage, Long, CadenceMessageWorkflowRequest> prepare) {
    transaction.executeWithoutResult(
        status -> {
          Item item = lock(claim);
          if (item == null || item.startPayload() != null) return;
          IncomingMessage message = codec.decode(item.messagePayload(), IncomingMessage.class);
          CadenceMessageWorkflowRequest request = prepare.apply(message, claim.id());
          if (request == null) {
            complete(claim);
          } else {
            jdbc.update(
                "UPDATE agent_inbound_messages SET message_payload = NULL, start_payload = ? WHERE inbox_id = ?",
                codec.encode(request),
                claim.id());
          }
        });
  }

  void dispatch(Claim claim, Consumer<CadenceMessageWorkflowRequest> start) {
    transaction.executeWithoutResult(
        status -> {
          Item item = lock(claim);
          if (item == null) return;
          start.accept(codec.decode(item.startPayload(), CadenceMessageWorkflowRequest.class));
          complete(claim);
        });
  }

  void retry(Claim claim) {
    var retryAt = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(6, claim.attempt())));
    jdbc.update(
        """
        UPDATE agent_inbound_messages SET available_at = ?, claim_token = NULL, claimed_until = NULL
         WHERE inbox_id = ? AND claim_token = ? AND completed_at IS NULL
        """,
        at(retryAt),
        claim.id(),
        claim.token());
  }

  void deleteCompletedBefore(Instant cutoff) {
    jdbc.update("DELETE FROM agent_inbound_messages WHERE completed_at < ?", at(cutoff));
  }

  private Item lock(Claim claim) {
    return jdbc
        .query(
            """
        SELECT message_payload, start_payload FROM agent_inbound_messages
         WHERE inbox_id = ? AND claim_token = ? AND completed_at IS NULL FOR UPDATE
        """,
            (row, index) -> new Item(row.getString(1), row.getString(2)),
            claim.id(),
            claim.token())
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void complete(Claim claim) {
    jdbc.update(
        """
        UPDATE agent_inbound_messages
           SET completed_at = ?, message_payload = NULL, start_payload = NULL, claim_token = NULL,
               claimed_until = NULL
         WHERE inbox_id = ?
        """,
        at(Instant.now()),
        claim.id());
  }

  private static Timestamp at(Instant value) {
    return Timestamp.from(value);
  }

  record Claim(long id, String token, int attempt) {}

  private record Item(String messagePayload, String startPayload) {}
}
