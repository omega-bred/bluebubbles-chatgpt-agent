package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.server.agent.AgentStateCodec;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.SharedAgentTestDatabase;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import io.breland.bbagent.server.config.BBChatGptAgentConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

class InboundMessageInboxTest {
  @Test
  void concurrentRedeliveryIsAcceptedOnceAndAPreparedStartSurvivesWorkerFailure() throws Exception {
    try (var db = new SharedAgentTestDatabase();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = inbox(db);
      var second = inbox(db);
      var message = message("first", "introduce yourself");
      var next = message("second", "what do you remember?");
      var a = executor.submit(() -> first.accept(message));
      var b = executor.submit(() -> second.accept(message));
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
      second.accept(next);

      Instant now = Instant.now();
      var originalClaim = first.claimNext(now);
      assertThat(second.claimNext(now)).isNull();
      first.prepare(originalClaim, (input, version) -> request(input, version));
      assertThatThrownBy(
              () ->
                  first.dispatch(
                      originalClaim,
                      ignored -> {
                        throw new IllegalStateException("worker lost after preparation");
                      }))
          .isInstanceOf(IllegalStateException.class);

      var recovered = second.claimNext(now.plus(Duration.ofMinutes(3)));
      assertThat(recovered.id()).isEqualTo(originalClaim.id());
      second.prepare(
          recovered,
          (input, version) -> {
            throw new AssertionError("do not repeat preparation after a crash");
          });
      second.dispatch(
          recovered,
          start -> {
            assertThat(start.message()).isEqualTo(message);
            assertThat(start.workflowContext().responseVersion()).isEqualTo(recovered.id());
          });
      var nextClaim = first.claimNext(Instant.now());
      first.prepare(
          nextClaim,
          (input, version) -> {
            assertThat(input).isEqualTo(next);
            return null;
          });
      assertThat(second.claimNext(Instant.now())).isNull();
      var jdbc = new JdbcTemplate(db.connect());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_inbound_messages", Long.class))
          .isEqualTo(2L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM agent_inbound_messages WHERE message_payload IS NOT NULL OR start_payload IS NOT NULL",
                  Long.class))
          .isZero();
    }
  }

  private static InboundMessageInbox inbox(SharedAgentTestDatabase db) {
    var connection = db.connect();
    return new InboundMessageInbox(
        new JdbcTemplate(connection),
        new AgentStateCodec(new BBChatGptAgentConfig().getObjectMapper()),
        new JdbcTransactionManager(connection));
  }

  private static IncomingMessage message(String guid, String text) {
    return new IncomingMessage(
            "test-chat",
            guid,
            null,
            text,
            false,
            "iMessage",
            "synthetic-sender",
            false,
            Instant.parse("2026-11-20T15:00:00Z"),
            List.of(),
            false)
        .withVerifiedBlueBubblesInbound(List.of());
  }

  private static CadenceMessageWorkflowRequest request(IncomingMessage message, long version) {
    return new CadenceMessageWorkflowRequest(
        new AgentWorkflowContext(
            "test-chat:turn:" + version,
            message.chatGuid(),
            message.messageGuid(),
            message.timestamp(),
            DecisionRouting.EMPTY,
            version),
        message,
        null);
  }
}
