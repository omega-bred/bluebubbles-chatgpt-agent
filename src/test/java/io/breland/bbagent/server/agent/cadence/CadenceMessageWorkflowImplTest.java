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

  @Test
  void deliversImageGeneratedBeforeAFollowupFunctionCall() throws Exception {
    var lookup = new CadenceToolCall("call-1", "lookup", "{}");
    var first =
        new CadenceResponseBundle(
            "{\"id\":\"response-1\",\"output\":[{\"id\":\"ig-first\",\"type\":\"image_generation_call\",\"status\":\"completed\"}]}",
            "",
            "[]",
            List.of(lookup));
    var last =
        new CadenceResponseBundle(
            "{\"id\":\"response-2\",\"output\":[]}", "Here is the image", "[]", List.of());
    when(activities.createResponseBundle("[]", message, context)).thenReturn(first, last);
    when(activities.executeToolCallsJson(List.of(lookup), message, context)).thenReturn("[]");
    when(activities.handleGeneratedImages(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Here is the image"),
            org.mockito.ArgumentMatchers.eq(message),
            org.mockito.ArgumentMatchers.eq(context)))
        .thenReturn(new ImageSendResult(true, true));

    new CadenceMessageWorkflowImpl(activities).run(request);

    var json = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(activities)
        .handleGeneratedImages(
            json.capture(),
            org.mockito.ArgumentMatchers.eq("Here is the image"),
            org.mockito.ArgumentMatchers.eq(message),
            org.mockito.ArgumentMatchers.eq(context));
    var output =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.getValue()).path("output");
    org.junit.jupiter.api.Assertions.assertEquals(1, output.size());
    org.junit.jupiter.api.Assertions.assertEquals("ig-first", output.get(0).path("id").asText());
    org.junit.jupiter.api.Assertions.assertFalse(output.get(0).has("result"));
    verify(activities).finalizeWorkflow(message, context, true);
    verify(activities, org.mockito.Mockito.never())
        .sendThreadAwareText(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  private static CadenceResponseBundle finalBundle(String text) {
    return new CadenceResponseBundle("{}", text, "[]", List.of());
  }
}
