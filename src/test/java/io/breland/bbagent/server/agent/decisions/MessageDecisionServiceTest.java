package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class MessageDecisionServiceTest {
  private final DecisionsClient client = mock(DecisionsClient.class);
  private final DecisionsProperties properties = new DecisionsProperties();
  private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
  private final ConversationState state = new ConversationState();
  private final MessageDecisionService service =
      new MessageDecisionService(client, properties, tools, new OperationalMetricsService(null));

  @BeforeEach
  void setup() {
    when(tools.availableTools(any())).thenReturn(List.of());
    result(Map.of("no_action", 1.0, "continues_task", 0.0, "feedback", 0.0, "reaction_only", 0.0));
  }

  private void result(Map<String, Double> probabilities) {
    when(client.evaluate(any(), any(), any()))
        .thenReturn(new DecisionsClient.Result(probabilities));
  }

  static IncomingMessage message(String text) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;test",
        "message-1",
        null,
        text,
        false,
        "iMessage",
        "user@example.com",
        true,
        Instant.parse("2026-10-18T18:00:00Z"),
        List.of(),
        false);
  }

  private MessageDecisionService.Assessment assess(String text) {
    return service.assess(message(text), state, AssistantResponsiveness.DEFAULT);
  }

  @Test
  void confidentNonActionCanBeSuppressed() {
    assertThat(assess("thanks").suppress()).isTrue();
    assertThat(assess("thanks").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.NO_RESPONSE);
  }

  private void enableReaction() {
    when(tools.resolveTool(eq("send_reaction"), any()))
        .thenReturn(new AgentTool("send_reaction", "React", null, false, null));
    result(
        Map.of(
            "no_action",
            0.99,
            "continues_task",
            0.0,
            "feedback",
            0.0,
            "reaction_only",
            0.99,
            "reaction_love",
            0.97));
  }

  @Test
  void confidentCelebrationChoosesAnEmojiInsteadOfSilenceOrText() {
    enableReaction();
    var assessment = assess("I passed my exam!");
    assertThat(assessment.suppress()).isFalse();
    assertThat(assessment.routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.REACTION);
    assertThat(assessment.routing().reaction()).isEqualTo("love");
  }

  @Test
  void uncertainOrRefusedReactionDoesNotSilenceTheMessage() {
    enableReaction();
    result(Map.of("no_action", 0.99, "continues_task", 0.0, "feedback", 0.0));
    assertThat(assess("I passed!").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
    result(
        Map.of(
            "no_action",
            0.99,
            "continues_task",
            0.0,
            "feedback",
            0.0,
            "reaction_only",
            0.99,
            "reaction_love",
            0.5));
    assertThat(assess("I passed!").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
  }

  @Test
  void directRequestsAndTaskAnswersAreNeverReplacedByAnEmoji() {
    enableReaction();
    assertThat(assess("Chat, help me").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
    assertThat(assess("yes").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
    result(
        Map.of(
            "no_action",
            0.99,
            "continues_task",
            0.95,
            "feedback",
            0.0,
            "reaction_only",
            0.99,
            "reaction_like",
            0.99));
    assertThat(assess("Friday works").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
  }

  @Test
  void reactionRequiresTransportSupportAndEnabledFlag() {
    enableReaction();
    properties.setReactionsEnabled(false);
    assertThat(assess("I passed!").routing().responseMode())
        .isNotEqualTo(DecisionRouting.ResponseMode.REACTION);
    properties.setReactionsEnabled(true);
    when(tools.resolveTool(eq("send_reaction"), any())).thenReturn(null);
    assertThat(assess("I passed!").routing().responseMode())
        .isEqualTo(DecisionRouting.ResponseMode.FULL_RESPONSE);
  }

  @Test
  void neverReactsToAnIncomingTapback() {
    enableReaction();
    assertThat(assess("Loved “Great work!”").routing().responseMode())
        .isNotEqualTo(DecisionRouting.ResponseMode.REACTION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "no", "stop!", "continue", "Chat, help me"})
  void explicitInvocationAndShortTaskRepliesCannotBeSuppressed(String text) {
    assertThat(assess(text).suppress()).isFalse();
  }

  @Test
  void contextContinuationOverridesNonActionScore() {
    result(Map.of("no_action", 1.0, "continues_task", 0.8, "feedback", 0.0));
    assertThat(assess("Loved “Should I continue?”").suppress()).isFalse();
  }

  @Test
  void refusedOrMissingSafetyQuestionsCannotSuppress() {
    result(Map.of("no_action", 1.0));
    assertThat(assess("thanks").suppress()).isFalse();
  }

  @Test
  void feedbackPreloadsRecordingWithoutExecutingIt() {
    result(Map.of("no_action", 1.0, "continues_task", 0.0, "feedback", 0.99));
    var assessment = assess("Your replies are too slow");
    assertThat(assessment.suppress()).isFalse();
    assertThat(assessment.routing().feedback()).isTrue();
    assertThat(assessment.routing().toolNames()).containsExactly("record_feedback");
  }

  @Test
  void selectsOnlyLikelyToolsFromTheAuthorizedCandidateList() {
    when(tools.availableTools(any()))
        .thenReturn(
            List.of(
                new AgentTool("usage", "Get usage limits", null, false, null),
                new AgentTool("calendar", "Read calendar", null, false, null)));
    result(Map.of("tool_0", 0.1, "tool_1", 0.99));
    assertThat(assess("How much quota is left?").routing().toolNames()).containsExactly("usage");
  }

  @Test
  void suppliesHistoryAndPendingMessagesAsEvidence() {
    state.addTurn(ConversationTurn.assistant("Which day works?", Instant.now()));
    state.recordPendingIncomingTurn(message("Friday"));
    assess("yes");
    var evidence = ArgumentCaptor.forClass(Object.class);
    verify(client).evaluate(any(), evidence.capture(), any());
    assertThat(evidence.getValue().toString())
        .contains("Which day works?", "Friday", "current_message=yes");
  }

  @Test
  void disabledMakesNoCalls() {
    properties.setEnabled(false);
    assertThat(assess("hello")).isEqualTo(MessageDecisionService.Assessment.CONTINUE);
    verifyNoInteractions(client, tools);
  }
}
