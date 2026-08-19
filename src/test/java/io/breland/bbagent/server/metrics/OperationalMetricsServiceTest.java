package io.breland.bbagent.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OperationalMetricsServiceTest {

  @Test
  void recordsToolInvocationCountersAndTimers() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordAgentToolInvocation(
        "imessage", "send_text", "bluebubbles", false, "tool_error", Duration.ofMillis(42));

    assertEquals(
        1.0,
        registry
            .get("bbagent.agent.tool.invocation.count")
            .tag("transport", "imessage")
            .tag("tool_name", "send_text")
            .tag("tool_category", "bluebubbles")
            .tag("outcome", "failure")
            .tag("failure_type", "tool_error")
            .counter()
            .count());
    assertEquals(
        1L,
        registry
            .get("bbagent.agent.tool.invocation.duration")
            .tag("transport", "imessage")
            .tag("tool_name", "send_text")
            .tag("tool_category", "bluebubbles")
            .tag("outcome", "failure")
            .tag("failure_type", "tool_error")
            .timer()
            .count());
  }

  @Test
  void recordsLlmCallCountersAndTimersWithModelTag() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordLlmCall(
        "lxmf", "agent_response", "Qwen/Qwen3.6-27B", true, null, Duration.ofMillis(123));

    assertEquals(
        1.0,
        registry
            .get("bbagent.agent.llm.call.count")
            .tag("transport", "lxmf")
            .tag("operation", "agent_response")
            .tag("model", "Qwen/Qwen3.6-27B")
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .counter()
            .count());
    assertEquals(
        1L,
        registry
            .get("bbagent.agent.llm.call.duration")
            .tag("transport", "lxmf")
            .tag("operation", "agent_response")
            .tag("model", "Qwen/Qwen3.6-27B")
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .timer()
            .count());
  }

  @Test
  void recordsBlueBubblesHealthGaugesAndCheckMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordBlueBubblesHealthCheck(false, false, "timeout", Duration.ofMillis(25));

    assertEquals(0.0, registry.get("bbagent.bluebubbles.health.up").gauge().value());
    assertEquals(0.0, registry.get("bbagent.bluebubbles.health.icloud.connected").gauge().value());
    assertEquals(
        1.0, registry.get("bbagent.bluebubbles.health.consecutive_failures").gauge().value());
    assertEquals(
        1.0,
        registry
            .get("bbagent.bluebubbles.health.check.count")
            .tag("outcome", "failure")
            .tag("icloud_connected", "false")
            .tag("failure_type", "timeout")
            .counter()
            .count());
  }

  @Test
  void recordsConversationMemoryExtractionMetricsWithoutIdentifiers() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordMemoryExtraction(false, "membership_refresh", Duration.ofMillis(75));
    service.recordMemoryExtractionCandidate("GROUP_DECISION", "CONFIRMED", true);
    service.recordMemoryWorkLag(Duration.ofSeconds(12));

    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.extraction.count")
            .tag("outcome", "failure")
            .tag("failure_type", "membership_refresh")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.extraction.candidate.count")
            .tag("kind", "group_decision")
            .tag("status", "confirmed")
            .tag("accepted", "true")
            .counter()
            .count());
    assertEquals(1L, registry.get("bbagent.memory.work.lag").timer().count());
  }

  @Test
  void recordsConversationMemoryPipelineMetricsAndBacklogWithoutIdentifiers() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordMemoryProjection("upsert", false, "mem0_write_failed", Duration.ofMillis(20));
    service.recordMemoryDigest("reconcile", true, null, Duration.ofMillis(30));
    service.recordMemoryCatchup(true, null, Duration.ofMillis(40));
    service.recordMemoryProactiveDelivery(
        "scheduled", false, "send_unconfirmed", Duration.ofMillis(50));
    service.recordMemoryCleanup("raw_message", 3, true, null, Duration.ofMillis(60));
    service.updateMemoryBacklog(Duration.ofSeconds(70), Duration.ofSeconds(80), 2);

    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.projection.count")
            .tag("operation", "upsert")
            .tag("outcome", "failure")
            .tag("failure_type", "mem0_write_failed")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.digest.count")
            .tag("operation", "reconcile")
            .tag("outcome", "success")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry.get("bbagent.memory.catchup.count").tag("outcome", "success").counter().count());
    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.proactive.delivery.count")
            .tag("delivery_mode", "scheduled")
            .tag("outcome", "failure")
            .counter()
            .count());
    assertEquals(
        3.0,
        registry
            .get("bbagent.memory.cleanup.item.count")
            .tag("operation", "raw_message")
            .counter()
            .count());
    assertEquals(
        70.0, registry.get("bbagent.memory.backlog.extraction.age.seconds").gauge().value());
    assertEquals(
        80.0, registry.get("bbagent.memory.backlog.projection.age.seconds").gauge().value());
    assertEquals(2.0, registry.get("bbagent.memory.backlog.failed.work").gauge().value());
    registry
        .getMeters()
        .forEach(
            meter ->
                meter
                    .getId()
                    .getTags()
                    .forEach(
                        tag ->
                            org.assertj.core.api.Assertions.assertThat(tag.getKey())
                                .doesNotContain(
                                    "account", "conversation", "message", "phone", "email")));
  }

  @Test
  void recordsProgressiveQuestionAnswerMetricsWithoutSensitiveTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordMemoryQuestionAnswer(
        "CLARIFICATION_REQUIRED",
        "openrouter/z-ai/glm-5.2",
        500,
        2,
        1,
        1,
        0,
        true,
        null,
        Duration.ofMillis(250));

    assertEquals(
        1.0,
        registry
            .get("bbagent.memory.question.answer.count")
            .tag("action", "clarification_required")
            .tag("model", "openrouter/z-ai/glm-5.2")
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .counter()
            .count());
    assertEquals(1L, registry.get("bbagent.memory.question.answer.duration").timer().count());
    assertEquals(
        500.0, registry.get("bbagent.memory.question.answer.message.count").counter().count());
    assertEquals(2.0, registry.get("bbagent.memory.question.answer.page.count").counter().count());
    assertEquals(
        1.0, registry.get("bbagent.memory.question.answer.window.count").counter().count());
    assertEquals(
        1.0, registry.get("bbagent.memory.question.answer.model.call.count").counter().count());
    assertEquals(
        0.0, registry.get("bbagent.memory.question.answer.reduction.count").counter().count());
    org.assertj.core.api.Assertions.assertThat(
            registry.find("bbagent.memory.question.answer.plan.count").counter())
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            registry.find("bbagent.memory.question.answer.verification.count").counter())
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            registry.getMeters().stream()
                .filter(
                    meter -> meter.getId().getName().startsWith("bbagent.memory.question.answer"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey))
        .containsOnly("action", "model", "outcome", "failure_type");
  }
}
