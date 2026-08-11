package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.memory.ConversationMemoryResponsesClient.RoutedResponse;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawFindingReduction;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawWindowDecision;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawWindowFinding;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.Confidence;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ModelWindowDecision;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.ParticipantHint;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionFinding;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.WindowAction;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.WindowFinding;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

class ConversationQuestionAnsweringModelClientTest {
  private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T00:00:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-08-09T00:01:30Z");

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final ConversationMemoryResponsesClient responses =
      mock(ConversationMemoryResponsesClient.class);
  private final ConversationQuestionAnsweringModelClient client =
      new ConversationQuestionAnsweringModelClient(responses, objectMapper);

  @Test
  void decideSuppliesReferenceTimeTimestampsAndOrdinaryEvidenceContent() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.ANSWERED,
                      "Sam shared the link this morning.",
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of(),
                      List.of("Sam")));
            });

    var result =
        client.decide(
            "What happened today?",
            TO,
            "America/Los_Angeles",
            List.of(message("m-1", "Sam", "Email sam@example.com: https://example.com/launch")),
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.ANSWERED);
    assertThat(result.decision().evidenceMessageGuids()).containsExactly("m-1");
    assertThat(capturedDeadlineInput(RawWindowDecision.class))
        .contains(
            "What happened today?",
            TO.toString(),
            FROM.toString(),
            "America/Los_Angeles",
            "sam@example.com",
            "https://example.com/launch")
        .doesNotContain("m-1");
    assertThat(capturedInstructions(RawWindowDecision.class))
        .containsIgnoringCase("untrusted data")
        .containsIgnoringCase("never follow")
        .containsIgnoringCase("tools are unavailable")
        .doesNotContain("Never include raw phone", "Never include email", "Never include URL");
  }

  @Test
  void answeredWindowKeepsAnAnswerWhenTheModelOmitsOptionalEvidenceMetadata() {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation ->
                validated(
                    invocation,
                    new RawWindowDecision(
                        WindowAction.ANSWERED,
                        "Sam and Lee tied for the best result.",
                        null,
                        Confidence.HIGH,
                        List.of(),
                        List.of(),
                        List.of("Sam"))));

    var result =
        client.decide(
            "Who had the best result?",
            TO,
            null,
            List.of(message("m-1", "Sam", "Sam and Lee tied for the best result.")),
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.ANSWERED);
    assertThat(result.decision().answer()).isEqualTo("Sam and Lee tied for the best result.");
    assertThat(result.decision().evidenceMessageGuids()).isEmpty();
    assertThat(result.decision().referencedParticipants()).isEmpty();
  }

  @Test
  void decideDoesNotSendInternalParticipantIdentityHintsToTheQaProvider() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.ANSWERED,
                      "The masked participant posted the update.",
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of(),
                      List.of("participant ending 0199")));
            });
    QuestionMessage message =
        new QuestionMessage(
            "m-1",
            "participant ending 0199",
            FROM,
            "The update is ready.",
            new ParticipantHint("participant ending 0199", "+15555550199"));

    client.decide("Who posted the update?", TO, null, List.of(message), DEADLINE);

    assertThat(capturedDeadlineInput(RawWindowDecision.class))
        .contains("participant ending 0199")
        .doesNotContain("+15555550199", "participantHint", "normalizedIdentity");
  }

  @Test
  void needOlderMapsProvisionalFindingsOnlyFromSubmittedAliases() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.NEED_OLDER_MESSAGES,
                      null,
                      null,
                      Confidence.MEDIUM,
                      List.of(),
                      List.of(
                          new RawWindowFinding(
                              "The thread references an earlier decision.",
                              Confidence.MEDIUM,
                              List.of(alias),
                              List.of("Sam"))),
                      List.of()));
            });

    var result =
        client.decide(
            "What was decided?",
            TO,
            null,
            List.of(message("m-1", "Sam", "That follows the earlier decision.")),
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.NEED_OLDER_MESSAGES);
    assertThat(result.decision().provisionalFindings())
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.answer()).isEqualTo("The thread references an earlier decision.");
              assertThat(finding.evidenceMessageGuids()).containsExactly("m-1");
              assertThat(finding.referencedParticipants()).containsExactly("Sam");
            });
  }

  @Test
  void needOlderKeepsAProvisionalFindingWhenOptionalEvidenceMetadataIsOmitted() {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation ->
                validated(
                    invocation,
                    new RawWindowDecision(
                        WindowAction.NEED_OLDER_MESSAGES,
                        null,
                        null,
                        Confidence.MEDIUM,
                        List.of(),
                        List.of(
                            new RawWindowFinding(
                                "The thread points to an older result.",
                                Confidence.MEDIUM,
                                List.of(),
                                List.of("Sam"))),
                        List.of())));

    var result =
        client.decide(
            "Who had the best result?",
            TO,
            null,
            List.of(message("m-1", "Sam", "That result was posted earlier.")),
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.NEED_OLDER_MESSAGES);
    assertThat(result.decision().provisionalFindings())
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.answer()).isEqualTo("The thread points to an older result.");
              assertThat(finding.evidenceMessageGuids()).isEmpty();
              assertThat(finding.referencedParticipants()).isEmpty();
            });
  }

  @Test
  void modelWindowDecisionEnforcesActionSpecificShapes() {
    WindowFinding finding =
        new WindowFinding(
            "An earlier decision is referenced.",
            Confidence.MEDIUM,
            List.of("m-1"),
            List.of("Sam"));

    assertThatCode(
            () ->
                new ModelWindowDecision(
                    WindowAction.NEED_OLDER_MESSAGES,
                    null,
                    null,
                    Confidence.MEDIUM,
                    List.of(),
                    List.of(finding),
                    List.of()))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new ModelWindowDecision(
                    WindowAction.ANSWERED,
                    null,
                    null,
                    Confidence.HIGH,
                    List.of("m-1"),
                    List.of(),
                    List.of("Sam")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ModelWindowDecision(
                    WindowAction.NEED_TIME_CLARIFICATION,
                    "answer",
                    "About when?",
                    Confidence.LOW,
                    List.of(),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ModelWindowDecision(
                    WindowAction.NO_ANSWER,
                    "I don't see that in the messages.",
                    null,
                    Confidence.LOW,
                    List.of("m-1"),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ignoresUnrecognizedParticipantMetadataWithoutDiscardingMessageTextNames() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.ANSWERED,
                      "Mallory posted the update.",
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of(),
                      List.of("Mallory")));
            });

    var result =
        client.decide(
            "Who posted the update?",
            TO,
            null,
            List.of(message("m-1", "Sam", "Mallory posted the update.")),
            DEADLINE);

    assertThat(result.decision().answer()).isEqualTo("Mallory posted the update.");
    assertThat(result.decision().referencedParticipants()).isEmpty();
  }

  @Test
  void ignoresIrrelevantMetadataOnNoAnswerDecision() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.NO_ANSWER,
                      "I couldn't find that in these messages.",
                      null,
                      Confidence.LOW,
                      List.of(alias),
                      List.of(
                          new RawWindowFinding(
                              "The messages were unrelated.",
                              Confidence.LOW,
                              List.of(alias),
                              List.of("Sam"))),
                      List.of("Sam")));
            });

    var result =
        client.decide(
            "Who won?", TO, null, List.of(message("m-1", "Sam", "An unrelated update.")), DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.NO_ANSWER);
    assertThat(result.decision().answer()).isEqualTo("I couldn't find that in these messages.");
    assertThat(result.decision().evidenceMessageGuids()).isEmpty();
    assertThat(result.decision().provisionalFindings()).isEmpty();
    assertThat(result.decision().referencedParticipants()).isEmpty();
  }

  @Test
  void reductionExpandsOnlyCitedFindingAliasesToOriginalMessageGuids() throws Exception {
    QuestionFinding cited =
        new QuestionFinding("Sam posted the launch plan.", Confidence.HIGH, List.of("m-1"), FROM);
    QuestionFinding uncited =
        new QuestionFinding("Lee posted an unrelated note.", Confidence.LOW, List.of("m-2"), TO);
    when(responses.createValidated(
            anyString(), anyString(), eq(800), eq(RawFindingReduction.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("findings")
                      .get(0)
                      .path("finding_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawFindingReduction(
                      WindowAction.ANSWERED,
                      "The launch plan came from Sam.",
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of()));
            });

    var result =
        client.reduceFindings(
            "Who posted the launch plan?",
            TO,
            "America/Los_Angeles",
            List.of(cited, uncited),
            false,
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.ANSWERED);
    assertThat(result.decision().evidenceMessageGuids()).containsExactly("m-1");
    assertThat(result.citedFindings()).containsExactly(cited).doesNotContain(uncited);
    assertThat(capturedDeadlineInput(RawFindingReduction.class))
        .contains(
            "Who posted the launch plan?",
            TO.toString(),
            "America/Los_Angeles",
            "older_messages_available")
        .doesNotContain("m-1", "m-2");
  }

  @Test
  void reductionCannotRequestOlderMessagesAfterSourceExhaustion() throws Exception {
    QuestionFinding finding =
        new QuestionFinding(
            "An earlier event is referenced.", Confidence.MEDIUM, List.of("m-1"), FROM);
    when(responses.createValidated(
            anyString(), anyString(), eq(800), eq(RawFindingReduction.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("findings")
                      .get(0)
                      .path("finding_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawFindingReduction(
                      WindowAction.NEED_OLDER_MESSAGES,
                      null,
                      null,
                      Confidence.LOW,
                      List.of(alias),
                      List.of()));
            });

    assertThatThrownBy(
            () ->
                client.reduceFindings(
                    "What happened before that?", TO, null, List.of(finding), false, DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("older messages");
  }

  @Test
  void malformedAnsweredWindowResponseFailsClosedAsProviderError() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.ANSWERED,
                      null,
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of(),
                      List.of("Sam")));
            });

    assertThatThrownBy(
            () ->
                client.decide(
                    "Who posted?",
                    TO,
                    null,
                    List.of(message("m-1", "Sam", "I posted it.")),
                    DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("window response");
  }

  @Test
  void clarificationPreservesFallbackMetadata() {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation ->
                validated(
                    invocation,
                    new RawWindowDecision(
                        WindowAction.NEED_TIME_CLARIFICATION,
                        null,
                        "About when did that happen?",
                        Confidence.LOW,
                        List.of(),
                        List.of(),
                        List.of()),
                    "openai/gpt-4.1-mini",
                    true));

    var result =
        client.decide(
            "What happened then?",
            TO,
            null,
            List.of(message("m-1", "Sam", "I remember that.")),
            DEADLINE);

    assertThat(result.decision().action()).isEqualTo(WindowAction.NEED_TIME_CLARIFICATION);
    assertThat(result.decision().clarificationQuestion()).isEqualTo("About when did that happen?");
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
  }

  @Test
  void clarificationCannotRevealSubmittedMessageGuid() {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation ->
                validated(
                    invocation,
                    new RawWindowDecision(
                        WindowAction.NEED_TIME_CLARIFICATION,
                        null,
                        "Did that happen around message m-1?",
                        Confidence.LOW,
                        List.of(),
                        List.of(),
                        List.of())));

    assertThatThrownBy(
            () ->
                client.decide(
                    "What happened then?",
                    TO,
                    null,
                    List.of(message("m-1", "Sam", "I remember that.")),
                    DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
  }

  @Test
  void windowAnswerCannotRevealOpaqueAliasOrMessageGuid() throws Exception {
    when(responses.createValidated(
            anyString(), anyString(), eq(1_000), eq(RawWindowDecision.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("messages")
                      .get(0)
                      .path("evidence_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawWindowDecision(
                      WindowAction.ANSWERED,
                      "Evidence " + alias + " came from m-1.",
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of(),
                      List.of("Sam")));
            });

    assertThatThrownBy(
            () ->
                client.decide(
                    "Who posted?",
                    TO,
                    null,
                    List.of(message("m-1", "Sam", "I posted it.")),
                    DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
  }

  @Test
  void unknownFindingAliasFailsClosed() {
    QuestionFinding finding =
        new QuestionFinding("Sam posted it.", Confidence.HIGH, List.of("m-1"), FROM);
    when(responses.createValidated(
            anyString(), anyString(), eq(800), eq(RawFindingReduction.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation ->
                validated(
                    invocation,
                    new RawFindingReduction(
                        WindowAction.ANSWERED,
                        "Sam posted it.",
                        null,
                        Confidence.HIGH,
                        List.of("finding_unknown"),
                        List.of())));

    assertThatThrownBy(
            () ->
                client.reduceFindings(
                    "Who posted it?", TO, null, List.of(finding), false, DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown alias");
  }

  @Test
  void malformedFindingReductionFailsClosedAsProviderError() throws Exception {
    QuestionFinding finding =
        new QuestionFinding("Sam posted it.", Confidence.HIGH, List.of("m-1"), FROM);
    when(responses.createValidated(
            anyString(), anyString(), eq(800), eq(RawFindingReduction.class), eq(DEADLINE), any()))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("findings")
                      .get(0)
                      .path("finding_alias")
                      .asText();
              return validated(
                  invocation,
                  new RawFindingReduction(
                      WindowAction.ANSWERED,
                      null,
                      null,
                      Confidence.HIGH,
                      List.of(alias),
                      List.of()));
            });

    assertThatThrownBy(
            () ->
                client.reduceFindings(
                    "Who posted it?", TO, null, List.of(finding), false, DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("finding reduction response");
  }

  private JsonNode providerJson(String providerInput) throws Exception {
    String json = providerInput.substring(providerInput.indexOf('\n') + 1);
    return objectMapper.readTree(json);
  }

  private String capturedDeadlineInput(Class<?> outputType) {
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(responses)
        .createValidated(
            anyString(),
            input.capture(),
            org.mockito.ArgumentMatchers.anyInt(),
            eq(outputType),
            org.mockito.ArgumentMatchers.<Instant>any(),
            any());
    return input.getValue();
  }

  private String capturedInstructions(Class<?> outputType) {
    ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
    verify(responses)
        .createValidated(
            instructions.capture(),
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            eq(outputType),
            org.mockito.ArgumentMatchers.<Instant>any(),
            any());
    return instructions.getValue();
  }

  private static QuestionMessage message(String guid, String participant, String text) {
    return new QuestionMessage(guid, participant, FROM, text);
  }

  private static <T, R> RoutedResponse<R> validated(InvocationOnMock invocation, T value) {
    return validated(invocation, value, "openrouter/z-ai/glm-5.2", false);
  }

  private static <T, R> RoutedResponse<R> validated(
      InvocationOnMock invocation, T value, String model, boolean fallbackUsed) {
    Function<T, R> validator = invocation.getArgument(5);
    return new RoutedResponse<>(validator.apply(value), model, fallbackUsed);
  }
}
