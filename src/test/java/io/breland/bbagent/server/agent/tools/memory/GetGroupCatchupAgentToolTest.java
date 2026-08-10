package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupGroup;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.GroupQuestionResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.QuestionGroup;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantHint;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetGroupCatchupAgentToolTest {
  private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
  private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-02T00:00:00Z");
  private static final ParticipantHint HINT =
      new ParticipantHint("participant ending 0199", "+15555550199");

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
    when(digestService.catchUp(any(), any(), any(), any()))
        .thenReturn(new CatchupResult(List.of(), List.of()));
    when(digestService.answerQuestion(any(), any(), any(), any(), any(), any()))
        .thenReturn(new GroupQuestionResult(List.of(), List.of()));
    when(digestService.answerQuestionForChat(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new GroupQuestionResult(List.of(), List.of()));
  }

  @Test
  void descriptionExplainsProgressiveQuestionModeWithoutTopicsOrInternalJargon() {
    String description = new GetGroupCatchupAgentTool(scopeResolver).getTool().description();

    assertThat(description)
        .containsIgnoringCase("question")
        .containsIgnoringCase("recent messages")
        .containsIgnoringCase("older")
        .containsIgnoringCase("approximate time")
        .doesNotContainIgnoringCase(
            "authorized",
            "authoritative",
            "coverage",
            "evidence",
            "count",
            "score",
            "game",
            "puzzle",
            "round",
            "wordle",
            "wordling");
  }

  @Test
  void summaryModeRetainsStructuredCatchupResponse() throws Exception {
    Instant from = NOW.minusSeconds(6 * 60 * 60);
    when(digestService.catchUp("account-1", "Trip", from, NOW))
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

    JsonNode group =
        mapper
            .readTree(invokeTool("{\"group\":\"Trip\",\"lookback_hours\":6}"))
            .path("groups")
            .get(0);

    assertThat(group.path("group").asText()).isEqualTo("Trip");
    assertThat(group.path("decisions").get(0).asText()).isEqualTo("Meet Saturday.");
    assertThat(group.path("coverage_through").asText()).isEqualTo(NOW.minusSeconds(60).toString());
    assertThat(group.has("answer")).isFalse();
  }

  @Test
  void relativeQuestionDoesNotManufactureTwentyFourHourRange() throws Exception {
    invokeTool("{\"group\":\"Project chat\",\"question\":\"What happened today?\"}");

    verify(digestService)
        .answerQuestion("account-1", "Project chat", "What happened today?", null, NOW, null);
    verify(digestService, never()).catchUp(any(), any(), any(), any());
  }

  @Test
  void explicitQuestionRangeAndTimezoneArePassedAsHardContext() throws Exception {
    invokeTool(
        "{\"group\":\"Project chat\",\"question\":\"What changed?\","
            + "\"from\":\""
            + FROM
            + "\",\"to\":\""
            + TO
            + "\",\"timezone\":\"America/Los_Angeles\"}");

    verify(digestService)
        .answerQuestion(
            "account-1", "Project chat", "What changed?", FROM, TO, "America/Los_Angeles");
  }

  @Test
  void explicitQuestionLookbackCreatesAHardLowerBound() throws Exception {
    invokeTool("{\"group\":\"Project chat\",\"question\":\"What changed?\",\"lookback_hours\":48}");

    verify(digestService)
        .answerQuestion(
            "account-1",
            "Project chat",
            "What changed?",
            NOW.minusSeconds(48L * 60 * 60),
            NOW,
            null);
  }

  @Test
  void groupContextIgnoresRequestedGroupAndQueriesOnlyItself() throws Exception {
    when(context.message()).thenReturn(message(true));

    invokeTool("{\"group\":\"Some Other Group\",\"question\":\"What changed today?\"}");

    verify(digestService)
        .answerQuestionForChat(
            "account-1",
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;+;group-1",
            "What changed today?",
            null,
            NOW,
            null);
    verify(digestService, never())
        .answerQuestion(eq("account-1"), eq("Some Other Group"), any(), any(), any(), any());
  }

  @Test
  void questionResponseContainsOnlyNaturalAnswerAndIdentityHints() throws Exception {
    stubQuestionResult(answered("Sam posted the update.", List.of(HINT)));

    JsonNode group = mapper.readTree(invokeQuestion()).path("groups").get(0);

    assertThat(group.path("answer").asText()).isEqualTo("Sam posted the update.");
    assertThat(group.path("unresolved_participants").get(0).path("label").asText())
        .isEqualTo(HINT.label());
    assertThat(group.path("unresolved_participants").get(0).path("identity").asText())
        .isEqualTo(HINT.normalizedIdentity());
    assertThat(group.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("group", "answer", "unresolved_participants");
  }

  @Test
  void clarificationResponseContainsNoInternalState() throws Exception {
    stubQuestionResult(clarification("About when did that happen?"));

    String response = invokeQuestion();
    JsonNode group = mapper.readTree(response).path("groups").get(0);

    assertThat(group.path("clarification_question").asText())
        .isEqualTo("About when did that happen?");
    assertThat(group.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("group", "clarification_question");
    assertThat(response)
        .doesNotContain(
            "authorized",
            "coverage",
            "evidence",
            "insufficient_evidence",
            "retrieval_mode",
            "model",
            "cursor");
  }

  @Test
  void summaryModeStillDefaultsToTwentyFourHoursAndAcceptsDeepLookback() throws Exception {
    invokeTool("{\"group\":\"Trip\"}");
    invokeTool("{\"group\":\"Trip\",\"lookback_hours\":1440}");

    verify(digestService).catchUp("account-1", "Trip", NOW.minusSeconds(86_400), NOW);
    verify(digestService).catchUp("account-1", "Trip", NOW.minusSeconds(1440L * 60 * 60), NOW);
  }

  @Test
  void invalidQuestionRangeAndTimezoneFailBeforeHistory() throws Exception {
    assertThat(
            invokeTool(
                "{\"question\":\"What changed?\",\"from\":\"2026-08-08T12:00:00Z\","
                    + "\"to\":\"2026-08-08T11:00:00Z\"}"))
        .isEqualTo("invalid question range");
    assertThat(invokeTool("{\"question\":\"What changed?\",\"timezone\":\"Not/AZone\"}"))
        .isEqualTo("invalid question range");
    verify(digestService, never()).answerQuestion(any(), any(), any(), any(), any(), any());
  }

  @Test
  void groupContextRejectsAMissingCurrentChatGuidBeforeServiceInvocation() throws Exception {
    when(context.message()).thenReturn(message(true, null));

    assertThat(invokeTool("{\"question\":\"What changed?\"}"))
        .isEqualTo("current group chat unavailable");
    verifyNoInteractions(digestService);
  }

  private String invokeTool(String json) throws Exception {
    return new GetGroupCatchupAgentTool(scopeResolver)
        .getTool()
        .handler()
        .apply(context, mapper.readTree(json));
  }

  private String invokeQuestion() throws Exception {
    return invokeTool("{\"group\":\"Project chat\",\"question\":\"Who posted the update?\"}");
  }

  private void stubQuestionResult(GroupQuestionAnswer answer) {
    when(digestService.answerQuestion(
            "account-1", "Project chat", "Who posted the update?", null, NOW, null))
        .thenReturn(
            new GroupQuestionResult(List.of(new QuestionGroup("Project chat", answer)), List.of()));
  }

  private static GroupQuestionAnswer answered(String answer, List<ParticipantHint> hints) {
    return new GroupQuestionAnswer(AnswerStatus.ANSWERED, answer, null, hints, "test-model", false);
  }

  private static GroupQuestionAnswer clarification(String question) {
    return new GroupQuestionAnswer(
        AnswerStatus.CLARIFICATION_REQUIRED, null, question, List.of(), "test-model", false);
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
