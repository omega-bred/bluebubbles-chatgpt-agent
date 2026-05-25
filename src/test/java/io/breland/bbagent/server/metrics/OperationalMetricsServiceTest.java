package io.breland.bbagent.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OperationalMetricsServiceTest {

  @Test
  void recordsToolInvocationCountersAndTimers() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OperationalMetricsService service = new OperationalMetricsService(registry);

    service.recordAgentToolInvocation(
        "send_text", "bluebubbles", false, "tool_error", Duration.ofMillis(42));

    assertEquals(
        1.0,
        registry
            .get("bbagent.agent.tool.invocation.count")
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
            .tag("tool_name", "send_text")
            .tag("tool_category", "bluebubbles")
            .tag("outcome", "failure")
            .tag("failure_type", "tool_error")
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
}
