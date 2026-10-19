package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.*;
import io.breland.bbagent.server.agent.llm.LlmProvider;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionResponseRouterTest {
  private final IncomingMessage message = MessageDecisionServiceTest.message("I passed!");
  private final AgentToolRegistry tools = DecisionFixtures.toolRegistry("bluebubbles");
  private final AgentWorkflowContext context =
      new AgentWorkflowContext(
          "workflow",
          message.chatGuid(),
          message.messageGuid(),
          Instant.EPOCH,
          new DecisionRouting(List.of(), false, DecisionRouting.ResponseMode.REACTION, "love"));

  @Test
  void runsExistingReactionToolThenEmitsNoResponseWithoutCallingAModel() {
    var provider = mock(LlmProvider.class);
    var picker = mock(ModelPicker.class);
    var creator =
        new AgentResponseCreator(
            picker,
            tools,
            provider,
            new OperationalMetricsService(null),
            mock(AgentProfileService.class));
    var response = creator.createResponse(List.of(), message, context);
    var calls = AgentResponseHelper.extractFunctionCalls(response);
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().name()).isEqualTo("send_reaction");
    assertThat(calls.getFirst().arguments()).isEqualTo("{\"reaction\":\"love\"}");
    var toolContext = mock(io.breland.bbagent.server.agent.tools.ToolContext.class);
    when(toolContext.getMapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
    when(toolContext.message()).thenReturn(message);
    when(toolContext.canSendResponses()).thenReturn(true);
    when(toolContext.sendReaction(message.chatGuid(), message.messageGuid(), "love", null))
        .thenReturn(true);
    var factory = mock(io.breland.bbagent.server.agent.tools.ToolContextFactory.class);
    when(factory.create(message, context)).thenReturn(toolContext);
    var runner =
        new AgentToolActivityRunner(
            new com.fasterxml.jackson.databind.ObjectMapper(),
            factory,
            tools,
            mock(io.breland.bbagent.server.metrics.AgentMetricsService.class));
    var output = runner.run(calls.getFirst(), message, context);
    var finished =
        creator.createResponse(
            List.of(ResponseInputItem.ofFunctionCall(calls.getFirst()), output), message, context);
    assertThat(AgentResponseHelper.extractFunctionCalls(finished)).isEmpty();
    assertThat(AgentResponseHelper.extractResponseText(finished)).isEqualTo("NO_RESPONSE");
    verifyNoInteractions(provider, picker);
    verify(toolContext).sendReaction(message.chatGuid(), message.messageGuid(), "love", null);
    verify(toolContext).recordAssistantTurn("[reaction: love]");
  }

  @Test
  void failedOrUnconfirmedReactionFallsBackWithoutRepeatingToolCall() {
    var first = DecisionResponseRouter.route(List.of(), message, context, tools);
    var call = AgentResponseHelper.extractFunctionCalls(first).getFirst();
    var inputCall = ResponseInputItem.ofFunctionCall(call);
    assertThat(DecisionResponseRouter.route(List.of(inputCall), message, context, tools)).isNull();
    var failed =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(call.callId())
                .output("reaction unsupported")
                .build());
    assertThat(DecisionResponseRouter.route(List.of(inputCall, failed), message, context, tools))
        .isNull();
  }

  @Test
  void unsupportedTransportFallsBackAndInvalidReactionCannotBeInjected() {
    assertThat(
            DecisionResponseRouter.route(
                List.of(), message, context, DecisionFixtures.toolRegistry("lxmf")))
        .isNull();
    assertThat(
            new DecisionRouting(
                    List.of(),
                    false,
                    DecisionRouting.ResponseMode.REACTION,
                    "love\",\"selectedMessageGuid\":\"other")
                .responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
    assertThat(
            new DecisionRouting(List.of(), false, DecisionRouting.ResponseMode.REACTION, null)
                .responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
  }

  @Test
  void noResponseRoutingProducesTheExistingSilenceSentinel() {
    var silent =
        new AgentWorkflowContext(
            "w",
            message.chatGuid(),
            message.messageGuid(),
            Instant.EPOCH,
            new DecisionRouting(List.of(), false, DecisionRouting.ResponseMode.NO_RESPONSE, null));
    var response = DecisionResponseRouter.route(List.of(), message, silent, tools);
    assertThat(AgentResponseHelper.extractResponseText(response)).isEqualTo("NO_RESPONSE");
  }
}
