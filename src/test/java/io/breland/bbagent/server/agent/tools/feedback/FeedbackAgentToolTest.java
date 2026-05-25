package io.breland.bbagent.server.agent.tools.feedback;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.feedback.FeedbackService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedbackAgentToolTest {

  @Test
  void recordsFeedbackForCurrentAccount() {
    FeedbackService feedbackService = Mockito.mock(FeedbackService.class);
    when(feedbackService.recordFeedback(
            any(), eq("account-1"), eq("Can you support voice notes?"), eq("capability")))
        .thenReturn(
            new FeedbackService.RecordedFeedback(
                "feedback-1", Instant.parse("2026-05-01T00:00:00Z")));
    FeedbackAgentTool tool = new FeedbackAgentTool(feedbackService);
    ObjectMapper mapper = new ObjectMapper();
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    AgentProfile profile = Mockito.mock(AgentProfile.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    when(profile.resolveOrCreateAccountId(any())).thenReturn(Optional.of("account-1"));
    ToolContext context = new ToolContext(agent, profile, message(), null);
    var args = mapper.createObjectNode();
    args.put("feedback", "Can you support voice notes?");
    args.put("category", "capability");

    String output = tool.getTool().handler().apply(context, args);

    assertTrue(output.contains("feedback-1"));
    assertTrue(output.contains("recorded"));
    verify(feedbackService)
        .recordFeedback(
            any(), eq("account-1"), eq("Can you support voice notes?"), eq("capability"));
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "Can you support voice notes?",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}
