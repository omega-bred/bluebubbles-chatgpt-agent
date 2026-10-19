package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.AgentResponseCreator;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.llm.LlmProvider;
import io.breland.bbagent.server.agent.llm.LlmRequest;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DecisionResponseTest {
  @Test
  void preloadsToolsAndFeedbackHintOnTheFirstModelCall() {
    var picker = mock(ModelPicker.class);
    when(picker.resolveModelAccess(any())).thenReturn(mock(ModelAccessService.ModelAccess.class));
    var provider = mock(LlmProvider.class);
    var creator =
        new AgentResponseCreator(
            picker,
            DecisionFixtures.toolRegistry("bluebubbles"),
            provider,
            new OperationalMetricsService(null),
            mock(AgentProfileService.class));
    var message = MessageDecisionServiceTest.message("BlueChat has a bug; check my limits");
    var context =
        new AgentWorkflowContext(
            "workflow",
            message.chatGuid(),
            message.messageGuid(),
            Instant.EPOCH,
            new DecisionRouting(List.of("get_usage_limits", "record_feedback"), true));
    creator.createResponse(List.of(), message, context);
    var request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(provider).createResponse(request.capture());
    assertThat(request.getValue().tools())
        .extracting(t -> t.name())
        .contains("toolSearchTool", "get_usage_limits", "record_feedback");
    assertThat(request.getValue().inputItems().toString())
        .contains("substantive product feedback", "not already been recorded");
  }
}
