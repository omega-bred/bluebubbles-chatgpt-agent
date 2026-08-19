package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CadenceMessageWorkflowImplTest {
  private final CadenceAgentActivities activities = mock(CadenceAgentActivities.class);
  private final IncomingMessage message =
      new IncomingMessage(
          "any;-;+15555550123",
          "message-guid",
          null,
          "hello",
          false,
          "iMessage",
          "+15555550123",
          false,
          Instant.parse("2026-08-10T12:00:00Z"),
          List.of(),
          false);
  private final AgentWorkflowContext context =
      new AgentWorkflowContext(
          "workflow-id",
          message.chatGuid(),
          message.messageGuid(),
          Instant.parse("2026-08-10T12:00:01Z"));
  private final CadenceMessageWorkflowRequest request =
      new CadenceMessageWorkflowRequest(context, message, null);

  @BeforeEach
  void setUp() {
    when(activities.notifyIfMessageResponseLimitExceeded(message, context)).thenReturn(false);
    when(activities.getConversationHistory(message)).thenReturn(List.of());
    when(activities.buildConversationInputJson(List.of(), message)).thenReturn("[]");
  }

  @Test
  void typingWrapsTheCompleteSuccessfulTurn() {
    CadenceResponseBundle bundle = finalBundle("done");
    when(activities.createResponseBundle("[]", message, context)).thenReturn(bundle);
    when(activities.handleGeneratedImages("{}", "done", message, context))
        .thenReturn(new ImageSendResult(false, false));
    when(activities.sendThreadAwareText(message, "done", context)).thenReturn(true);

    new CadenceMessageWorkflowImpl(activities).run(request);

    InOrder order = inOrder(activities);
    order.verify(activities).startTyping(message, context);
    order.verify(activities).createResponseBundle("[]", message, context);
    order.verify(activities).handleGeneratedImages("{}", "done", message, context);
    order.verify(activities).sendThreadAwareText(message, "done", context);
    order.verify(activities).finalizeWorkflow(message, context, true);
    order.verify(activities).stopTyping(message, context);
    verify(activities).startTyping(message, context);
    verify(activities).stopTyping(message, context);
  }

  @Test
  void typingRemainsActiveAcrossToolAndModelLoops() {
    CadenceToolCall toolCall = new CadenceToolCall("call-1", "lookup", "{}");
    CadenceResponseBundle toolBundle = new CadenceResponseBundle("{}", "", "[]", List.of(toolCall));
    CadenceResponseBundle finalBundle = finalBundle("finished");
    when(activities.createResponseBundle("[]", message, context))
        .thenReturn(toolBundle, finalBundle);
    when(activities.executeToolCallsJson(List.of(toolCall), message, context)).thenReturn("[]");
    when(activities.handleGeneratedImages("{}", "finished", message, context))
        .thenReturn(new ImageSendResult(false, false));
    when(activities.sendThreadAwareText(message, "finished", context)).thenReturn(true);

    new CadenceMessageWorkflowImpl(activities).run(request);

    InOrder order = inOrder(activities);
    order.verify(activities).startTyping(message, context);
    order.verify(activities).createResponseBundle("[]", message, context);
    order.verify(activities).executeToolCallsJson(List.of(toolCall), message, context);
    order.verify(activities).createResponseBundle("[]", message, context);
    order.verify(activities).handleGeneratedImages("{}", "finished", message, context);
    order.verify(activities).sendThreadAwareText(message, "finished", context);
    order.verify(activities).finalizeWorkflow(message, context, true);
    order.verify(activities).stopTyping(message, context);
    verify(activities, times(1)).startTyping(message, context);
    verify(activities, times(1)).stopTyping(message, context);
  }

  @Test
  void typingStopsWhenModelActivityFails() {
    when(activities.createResponseBundle("[]", message, context))
        .thenThrow(new IllegalStateException("model unavailable"));

    assertThatThrownBy(() -> new CadenceMessageWorkflowImpl(activities).run(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model unavailable");

    InOrder order = inOrder(activities);
    order.verify(activities).startTyping(message, context);
    order.verify(activities).createResponseBundle("[]", message, context);
    order.verify(activities).stopTyping(message, context);
  }

  @Test
  void typingActivityFailuresDoNotFailTheTurn() {
    CadenceResponseBundle bundle = finalBundle("done");
    when(activities.createResponseBundle("[]", message, context)).thenReturn(bundle);
    when(activities.handleGeneratedImages("{}", "done", message, context))
        .thenReturn(new ImageSendResult(false, false));
    when(activities.sendThreadAwareText(message, "done", context)).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("typing unavailable"))
        .when(activities)
        .startTyping(message, context);
    org.mockito.Mockito.doThrow(new IllegalStateException("typing unavailable"))
        .when(activities)
        .stopTyping(message, context);

    new CadenceMessageWorkflowImpl(activities).run(request);

    verify(activities).createResponseBundle("[]", message, context);
    verify(activities).sendThreadAwareText(message, "done", context);
    verify(activities).finalizeWorkflow(message, context, true);
  }

  private static CadenceResponseBundle finalBundle(String text) {
    return new CadenceResponseBundle("{}", text, "[]", List.of());
  }
}
