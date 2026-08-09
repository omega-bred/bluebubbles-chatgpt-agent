package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.Confidence;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.CoverageStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.RetrievalMode;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetGroupCatchupAgentToolTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final MemoryScopeResolver scopeResolver = mock(MemoryScopeResolver.class);
  private final ConversationDigestService digestService = mock(ConversationDigestService.class);
  private final ToolContext context = mock(ToolContext.class);

  @BeforeEach
  void setUp() {
    when(context.message()).thenReturn(message(false));
    when(context.canonicalAccountId()).thenReturn(Optional.of("account-1"));
    when(context.getMapper()).thenReturn(mapper);
    when(scopeResolver.conversationDigestService()).thenReturn(Optional.of(digestService));
    when(digestService.currentTime()).thenReturn(NOW);
    when(digestService.catchUp(any(), nullable(String.class), any(), any(), nullable(String.class)))
        .thenReturn(new CatchupResult(List.of(), List.of()));
    when(digestService.catchUpForChat(any(), any(), any(), any(), any(), nullable(String.class)))
        .thenReturn(new CatchupResult(List.of(), List.of()));
  }

  @Test
  void returnsStructuredCoverageForADirectChatRequest() throws Exception {
    Instant from = NOW.minusSeconds(6 * 60 * 60);
    when(digestService.catchUp("account-1", "Trip", from, NOW, null))
        .thenReturn(
            new CatchupResult(
                List.of(
                    new CatchupGroup(
                        "Trip",
                        "Saturday was selected.",
                        List.of("The group compared two days."),
                        List.of("Meet Saturday."),
                        List.of("Choose a restaurant."),
                        from,
                        NOW,
                        NOW.minusSeconds(60))),
                List.of()));

    String response =
        new GetGroupCatchupAgentTool(scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"group\":\"Trip\",\"lookback_hours\":6}"));

    var group = mapper.readTree(response).path("groups").get(0);
    assertThat(group.path("group").asText()).isEqualTo("Trip");
    assertThat(group.path("coverage_through").asText()).isEqualTo(NOW.minusSeconds(60).toString());
    assertThat(group.path("decisions").get(0).asText()).isEqualTo("Meet Saturday.");
    assertThat(group.has("question_answer")).isFalse();
  }

  @Test
  void groupContextIgnoresRequestedGroupAndQueriesOnlyItself() throws Exception {
    when(context.message()).thenReturn(message(true));

    invokeTool("{\"group\":\"Some Other Group\",\"question\":\"Who is winning?\"}");

    verify(digestService)
        .catchUpForChat(
            "account-1",
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;+;group-1",
            NOW.minusSeconds(86_400),
            NOW,
            "Who is winning?");
    verify(digestService, never())
        .catchUp(eq("account-1"), eq("Some Other Group"), any(), any(), any());
  }

  @Test
  void groupContextRejectsAMissingCurrentChatGuidBeforeServiceInvocation() throws Exception {
    when(context.message()).thenReturn(message(true, null));

    String response = invokeTool("{\"question\":\"Who is winning?\"}");

    assertThat(response).isEqualTo("current group chat unavailable");
    verifyNoInteractions(digestService);
  }

  @Test
  void surfacesSemanticDisambiguationOptions() throws Exception {
    Instant from = NOW.minusSeconds(86_400);
    when(digestService.catchUp("account-1", "Trip", from, NOW, null))
        .thenReturn(
            new CatchupResult(List.of(), List.of("Trip (last active 2026-08-08T12:00:00Z)")));

    String response =
        new GetGroupCatchupAgentTool(scopeResolver)
            .getTool()
            .handler()
            .apply(context, mapper.readTree("{\"group\":\"Trip\"}"));

    assertThat(mapper.readTree(response).path("disambiguation_required").asBoolean()).isTrue();
  }

  @Test
  void questionModeSerializesSafePrimaryModelMetadataWithoutEvidence() throws Exception {
    Instant from = NOW.minusSeconds(86_400);
    GroupQuestionAnswer answer =
        new GroupQuestionAnswer(
            AnswerStatus.ANSWERED,
            "The only reported score is participant ending 0199 with 4/6.",
            Confidence.HIGH,
            "private-model-name",
            false,
            1,
            RetrievalMode.EXACT_SEARCH,
            CoverageStatus.COMPLETE,
            from,
            NOW,
            NOW,
            null);
    stubQuestionAnswer(answer);

    String response =
        invokeTool("{\"group\":\"Wordling Wonders\",\"question\":\"Who is winning?\"}");

    var questionAnswer = mapper.readTree(response).path("groups").get(0).path("question_answer");
    assertThat(questionAnswer.path("status").asText()).isEqualTo("answered");
    assertThat(questionAnswer.path("confidence").asText()).isEqualTo("high");
    assertThat(questionAnswer.path("retrieval_mode").asText()).isEqualTo("exact_search");
    assertThat(questionAnswer.path("coverage_status").asText()).isEqualTo("complete");
    assertThat(questionAnswer.path("evidence_message_count").asInt()).isEqualTo(1);
    assertThat(questionAnswer.path("answer").asText()).contains("only reported score");
    assertThat(questionAnswer.path("model").asText()).isEqualTo("private-model-name");
    assertThat(questionAnswer.path("fallback_used").asBoolean()).isFalse();
    assertThat(questionAnswer.has("partial_reason")).isFalse();
    assertThat(response)
        .doesNotContain("message_guid", "evidence_message_guid", "Wordle 1,877 4/6+");
  }

  @Test
  void questionModeSerializesFallbackModelProvenance() throws Exception {
    Instant from = NOW.minusSeconds(86_400);
    stubQuestionAnswer(
        new GroupQuestionAnswer(
            AnswerStatus.ANSWERED,
            "The fallback model found one supported result.",
            Confidence.MEDIUM,
            "fallback-model",
            true,
            1,
            RetrievalMode.HYBRID,
            CoverageStatus.COMPLETE,
            from,
            NOW,
            NOW,
            null));

    String response =
        invokeTool("{\"group\":\"Wordling Wonders\",\"question\":\"Who is winning?\"}");

    var questionAnswer = mapper.readTree(response).path("groups").get(0).path("question_answer");
    assertThat(questionAnswer.path("model").asText()).isEqualTo("fallback-model");
    assertThat(questionAnswer.path("fallback_used").asBoolean()).isTrue();
  }

  @Test
  void questionModeSerializesModelLessTerminalProvenance() throws Exception {
    Instant from = NOW.minusSeconds(86_400);
    stubQuestionAnswer(
        new GroupQuestionAnswer(
            AnswerStatus.INSUFFICIENT_EVIDENCE,
            "There is insufficient evidence in the authorized group history to answer that question.",
            Confidence.LOW,
            null,
            false,
            0,
            RetrievalMode.CHRONOLOGICAL,
            CoverageStatus.COMPLETE,
            from,
            NOW,
            NOW,
            null));

    String response =
        invokeTool("{\"group\":\"Wordling Wonders\",\"question\":\"Who is winning?\"}");

    var questionAnswer = mapper.readTree(response).path("groups").get(0).path("question_answer");
    assertThat(questionAnswer.has("model")).isTrue();
    assertThat(questionAnswer.path("model").isNull()).isTrue();
    assertThat(questionAnswer.path("fallback_used").asBoolean()).isFalse();
  }

  @Test
  void defaultsToTwentyFourHoursAndAcceptsDeepExplicitLookback() throws Exception {
    invokeTool("{\"group\":\"Trip\"}");
    invokeTool("{\"group\":\"Trip\",\"lookback_hours\":1440}");

    verify(digestService).catchUp("account-1", "Trip", NOW.minusSeconds(24L * 60 * 60), NOW, null);
    verify(digestService)
        .catchUp("account-1", "Trip", NOW.minusSeconds(1440L * 60 * 60), NOW, null);
  }

  @Test
  void explicitFromAndToTakePrecedenceOverLookback() throws Exception {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");

    invokeTool(
        "{\"group\":\"Trip\",\"from\":\""
            + from
            + "\",\"to\":\""
            + to
            + "\",\"lookback_hours\":0}");

    verify(digestService).catchUp("account-1", "Trip", from, to, null);
  }

  @Test
  void rejectsInvalidLookbackAndExplicitRanges() throws Exception {
    assertThat(invokeTool("{\"lookback_hours\":0}")).isEqualTo("invalid catch-up range");
    assertThat(invokeTool("{\"lookback_hours\":-1}")).isEqualTo("invalid catch-up range");
    assertThat(invokeTool("{\"from\":\"2026-08-08T12:00:00Z\",\"to\":\"2026-08-08T11:00:00Z\"}"))
        .isEqualTo("invalid catch-up range");
    assertThat(invokeTool("{\"from\":\"not-an-instant\"}")).isEqualTo("invalid catch-up range");
    assertThat(invokeTool("{\"to\":\"-1000000000-01-01T00:00:00Z\",\"lookback_hours\":1}"))
        .isEqualTo("invalid catch-up range");
  }

  private String invokeTool(String json) throws Exception {
    return new GetGroupCatchupAgentTool(scopeResolver)
        .getTool()
        .handler()
        .apply(context, mapper.readTree(json));
  }

  private void stubQuestionAnswer(GroupQuestionAnswer answer) {
    when(digestService.catchUp(
            "account-1", "Wordling Wonders", answer.from(), answer.to(), "Who is winning?"))
        .thenReturn(
            new CatchupResult(
                List.of(
                    new CatchupGroup(
                        "Wordling Wonders",
                        "Daily summary without raw evidence.",
                        List.of(),
                        List.of(),
                        List.of(),
                        answer.from(),
                        answer.to(),
                        answer.coverageThrough(),
                        answer)),
                List.of()));
  }

  private static IncomingMessage message(boolean group) {
    return message(group, group ? "iMessage;+;group-1" : "iMessage;-;+15555550123");
  }

  private static IncomingMessage message(boolean group, String chatGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chatGuid,
        "message-1",
        null,
        "What did I miss?",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        group,
        NOW,
        List.of(),
        false);
  }
}
