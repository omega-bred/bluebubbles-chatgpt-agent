package io.breland.bbagent.server.agent.tools.limits;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.ratelimit.MessageResponseRateLimitService;
import io.breland.bbagent.server.ratelimit.RateLimitStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GetUsageLimitsAgentToolTest {

  @Test
  void serializesUsageLimitResponseWithStableSnakeCaseFields() {
    MessageResponseRateLimitService rateLimits =
        Mockito.mock(MessageResponseRateLimitService.class);
    IncomingMessage message = message();
    when(rateLimits.statusFor(message))
        .thenReturn(
            new MessageResponseRateLimitService.MessageResponseLimitStatus(
                true,
                "account-1",
                true,
                new RateLimitStatus(
                    "message_responses",
                    "Assistant responses",
                    "account",
                    "account-1",
                    10,
                    100,
                    Instant.parse("2026-04-01T00:00:00Z"),
                    Instant.parse("2026-05-01T00:00:00Z"))));
    GetUsageLimitsAgentTool tool = new GetUsageLimitsAgentTool(rateLimits);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(new ObjectMapper());
    ToolContext context = new ToolContext(agent, message, null);

    String output = tool.getTool().handler().apply(context, new ObjectMapper().createObjectNode());

    assertTrue(output.contains("\"account_id\":\"account-1\""));
    assertTrue(output.contains("\"is_premium\":true"));
    assertTrue(output.contains("\"window_start\":\"2026-04-01T00:00:00Z\""));
    assertTrue(output.contains("\"window_end\":\"2026-05-01T00:00:00Z\""));
    assertTrue(output.contains("\"user_facing_text\""));
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "how much usage do I have left?",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
