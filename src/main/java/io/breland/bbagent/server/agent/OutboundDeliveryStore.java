package io.breland.bbagent.server.agent;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboundDeliveryStore {
  public enum Outcome {
    RESERVED,
    IN_FLIGHT,
    DELIVERED,
    UNCERTAIN,
    DECLINED
  }

  public record Result(Outcome outcome, String receipt) {
    public boolean delivered() {
      return outcome == Outcome.DELIVERED;
    }
  }

  private record Claim(Result result, Instant startedAt, String reservationToken) {}

  private final JdbcTemplate jdbc;
  private final AgentStateCodec codec;
  private final TransactionTemplate transaction;

  public OutboundDeliveryStore(
      JdbcTemplate jdbc, AgentStateCodec codec, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.codec = codec;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  String id(Object... keyParts) {
    return UUID.nameUUIDFromBytes(codec.encode(keyParts).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  Result deliver(
      String id,
      BooleanSupplier stillCurrent,
      BooleanSupplier allowSend,
      Function<String, String> send,
      BiFunction<String, Instant, String> reconcile) {
    Claim claim = transaction.execute(status -> reserve(id, stillCurrent));
    Result result = claim.result();
    if (claim.reservationToken() != null) {
      if (!allowSend.getAsBoolean() || !beginSend(id, claim.reservationToken(), stillCurrent)) {
        transaction.executeWithoutResult(
            status ->
                jdbc.update(
                    """
            UPDATE agent_outbound_deliveries SET outcome = 'DECLINED', updated_at = ?
             WHERE delivery_id = ? AND outcome = 'RESERVED' AND reservation_token = ?
            """,
                    at(Instant.now()),
                    id,
                    claim.reservationToken()));
        return new Result(Outcome.DECLINED, null);
      }
      String receipt = send.apply(id);
      return finish(id, receipt == null ? Outcome.UNCERTAIN : Outcome.DELIVERED, receipt);
    }
    if ((result.outcome() == Outcome.UNCERTAIN || result.outcome() == Outcome.IN_FLIGHT)
        && reconcile != null) {
      String receipt = reconcile.apply(id, claim.startedAt());
      if (receipt != null) return finish(id, Outcome.DELIVERED, receipt);
    }
    return result;
  }

  private Claim reserve(String id, BooleanSupplier stillCurrent) {
    Instant now = Instant.now();
    if (!stillCurrent.getAsBoolean())
      return new Claim(new Result(Outcome.DECLINED, null), now, null);
    String token = UUID.randomUUID().toString();
    Timestamp lease = at(now.plusSeconds(30));
    int inserted =
        jdbc.update(
            """
        INSERT INTO agent_outbound_deliveries
          (delivery_id, outcome, started_at, updated_at, reservation_token, reserved_until)
        VALUES (?, 'RESERVED', ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
            id,
            at(now),
            at(now),
            token,
            lease);
    if (inserted == 1
        || jdbc.update(
                """
        UPDATE agent_outbound_deliveries SET reservation_token = ?, reserved_until = ?, updated_at = ?
         WHERE delivery_id = ? AND outcome = 'RESERVED' AND reserved_until < ?
        """,
                token,
                lease,
                at(now),
                id,
                at(now))
            == 1) {
      return new Claim(new Result(Outcome.RESERVED, null), now, token);
    }
    return jdbc.queryForObject(
        "SELECT outcome, receipt, started_at FROM agent_outbound_deliveries WHERE delivery_id = ?",
        (row, index) ->
            new Claim(
                new Result(Outcome.valueOf(row.getString(1)), row.getString(2)),
                row.getTimestamp(3).toInstant(),
                null),
        id);
  }

  private boolean beginSend(String id, String token, BooleanSupplier stillCurrent) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              if (!stillCurrent.getAsBoolean()) return false;
              Timestamp now = at(Instant.now());
              return jdbc.update(
                      """
          UPDATE agent_outbound_deliveries SET outcome = 'IN_FLIGHT', started_at = ?, updated_at = ?
           WHERE delivery_id = ? AND outcome = 'RESERVED' AND reservation_token = ?
          """,
                      now,
                      now,
                      id,
                      token)
                  == 1;
            }));
  }

  private Result finish(String id, Outcome outcome, String receipt) {
    return transaction.execute(
        status -> {
          jdbc.update(
              """
          UPDATE agent_outbound_deliveries SET outcome = ?, receipt = ?, updated_at = ?
           WHERE delivery_id = ? AND outcome <> 'DELIVERED'
          """,
              outcome.name(),
              receipt,
              at(Instant.now()),
              id);
          return jdbc.queryForObject(
              "SELECT outcome, receipt FROM agent_outbound_deliveries WHERE delivery_id = ?",
              (row, index) -> new Result(Outcome.valueOf(row.getString(1)), row.getString(2)),
              id);
        });
  }

  @Scheduled(cron = "0 20 4 * * *", zone = "UTC")
  public void cleanUp() {
    jdbc.update(
        "DELETE FROM agent_outbound_deliveries WHERE updated_at < ?",
        at(Instant.now().minus(Duration.ofDays(30))));
  }

  private static Timestamp at(Instant value) {
    return Timestamp.from(value);
  }
}
