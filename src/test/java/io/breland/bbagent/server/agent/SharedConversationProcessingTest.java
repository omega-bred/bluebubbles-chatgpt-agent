package io.breland.bbagent.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.breland.bbagent.server.agent.decisions.DecisionRouting;
import io.breland.bbagent.server.config.BBChatGptAgentConfig;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

class SharedConversationProcessingTest {
  private final AgentStateCodec codec =
      new AgentStateCodec(new BBChatGptAgentConfig().getObjectMapper());

  @Test
  void anotherPodSeesPendingTurnsAndRejectsAStaleDelivery() {
    try (var db = new SharedAgentTestDatabase()) {
      var a = db.connect();
      var b = db.connect();
      var gateway =
          new ConversationStateStore(new JdbcTemplate(a), codec, new JdbcTransactionManager(a));
      var worker =
          new ConversationStateStore(new JdbcTemplate(b), codec, new JdbcTransactionManager(b));
      var gate = new WorkflowResponseGate(worker);
      var deliveries =
          new OutboundDeliveryStore(new JdbcTemplate(b), codec, new JdbcTransactionManager(b));
      var earlier = message("m1", "first", Instant.parse("2026-11-20T15:00:00Z"));
      var later = message("m2", "second", earlier.timestamp().plusSeconds(1));
      gateway.update(
          "chat",
          ignored -> new ConversationState(),
          state -> {
            state.recordPendingIncomingTurn(earlier);
            state.setLatestResponseVersion(10L);
            return null;
          });
      var old = workflow(earlier, 10L);
      assertThat(worker.get("chat").pendingIncomingTurns()).hasSize(1);
      assertThat(gate.canSendResponsesForWorkflowRun(old, null)).isTrue();
      gateway.update(
          "chat",
          ignored -> new ConversationState(),
          state -> {
            state.recordPendingIncomingTurn(later);
            state.setLatestResponseVersion(11L);
            return null;
          });
      assertThat(worker.get("chat").pendingIncomingTurns()).hasSize(2);
      assertThat(worker.get("chat").isStaleIncomingMessage(earlier)).isTrue();
      var result =
          deliveries.deliver(
              deliveries.id("old reply"),
              () -> gate.canClaimDelivery(old),
              () -> true,
              ignored -> {
                throw new AssertionError("stale reply was sent");
              },
              null);
      assertThat(result.outcome()).isEqualTo(OutboundDeliveryStore.Outcome.DECLINED);
    }
  }

  @Test
  void anotherPodReconcilesAnAcceptedSendWithoutResending() {
    try (var db = new SharedAgentTestDatabase()) {
      var a = db.connect();
      var b = db.connect();
      var first =
          new OutboundDeliveryStore(new JdbcTemplate(a), codec, new JdbcTransactionManager(a));
      var second =
          new OutboundDeliveryStore(new JdbcTemplate(b), codec, new JdbcTransactionManager(b));
      String id = first.id("bluebubbles", "chat", "message", "reply");
      var acceptedByTransport = new AtomicReference<String>();
      assertThatThrownBy(
              () ->
                  first.deliver(
                      id,
                      () -> true,
                      () -> true,
                      wireId -> {
                        acceptedByTransport.set(wireId);
                        throw new IllegalStateException(
                            "worker stopped before recording its receipt");
                      },
                      null))
          .isInstanceOf(IllegalStateException.class);

      var result =
          second.deliver(
              second.id("bluebubbles", "chat", "message", "reply"),
              () -> true,
              () -> {
                throw new AssertionError("quota charged twice");
              },
              ignored -> {
                throw new AssertionError("duplicate send");
              },
              (wireId, started) ->
                  wireId.equals(acceptedByTransport.get()) ? "transport-receipt" : null);
      assertThat(result.receipt()).isEqualTo("transport-receipt");
      assertThat(result.delivered()).isTrue();
    }
  }

  private static IncomingMessage message(String id, String text, Instant at) {
    return new IncomingMessage(
        "chat", id, null, text, false, "iMessage", "synthetic", false, at, List.of(), false);
  }

  private static AgentWorkflowContext workflow(IncomingMessage message, long version) {
    return new AgentWorkflowContext(
        "chat:turn:" + version,
        "chat",
        message.messageGuid(),
        message.timestamp(),
        DecisionRouting.EMPTY,
        version);
  }
}
