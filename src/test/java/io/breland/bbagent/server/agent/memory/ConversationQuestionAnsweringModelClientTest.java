package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.memory.ConversationMemoryResponsesClient.RoutedResponse;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawQuestionAnswer;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawSearchPlan;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModelClient.RawSupportVerification;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.Confidence;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionFinding;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.QuestionMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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
  void plannerForwardsTheExactQuestionAndNormalizesDynamicLiteralTerms() {
    when(responses.create(anyString(), anyString(), eq(300), eq(RawSearchPlan.class)))
        .thenReturn(
            routed(
                new RawSearchPlan(
                    List.of(" Project Atlas ", "project atlas", "%"), null, null, null)));

    var plan = client.plan("Who owns Project Atlas?", FROM, TO);

    assertThat(plan.terms()).containsExactly("Project Atlas", "%");
    assertThat(capturedInputWithoutDeadline(RawSearchPlan.class))
        .contains("Who owns Project Atlas?", FROM.toString(), TO.toString())
        .doesNotContain("message_guid", "transcript");
  }

  @Test
  void plannerUsesTheOperationDeadline() {
    when(responses.create(anyString(), anyString(), eq(300), eq(RawSearchPlan.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSearchPlan(List.of("ready"), null, null, null)));

    assertThat(client.plan("What is ready?", FROM, TO, DEADLINE).terms()).containsExactly("ready");
  }

  @Test
  void rejectsConfiguredPlannerLimitsOutsideOneToFive() {
    assertThatThrownBy(
            () -> new ConversationQuestionAnsweringModelClient(responses, objectMapper, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ConversationQuestionAnsweringModelClient(responses, objectMapper, 6))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generationUsesHighEntropyAliasesWithoutExposingRealMessageIdentifiers() throws Exception {
    AtomicReference<String> alias = new AtomicReference<>();
    when(responses.create(anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class)))
        .thenAnswer(
            invocation -> {
              alias.set(submittedAliases(invocation.getArgument(1)).getFirst());
              return routed(
                  new RawQuestionAnswer(
                      "ANSWERED", "Alice owns the project.", "HIGH", List.of(alias.get()), false));
            });

    var result =
        client.answer(
            "Who owns the project?",
            List.of(message("real-message-guid", "Alice", "Alice is the owner.")));

    assertThat(result.answer().evidenceMessageGuids()).containsExactly("real-message-guid");
    assertThat(alias.get()).startsWith("ev_").hasSize(35);
    assertThat(capturedInputWithoutDeadline(RawQuestionAnswer.class))
        .contains(alias.get(), "Alice is the owner.")
        .doesNotContain("real-message-guid");
  }

  @Test
  void outputAliasRejectionIsDelimiterAware() {
    when(responses.create(anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class)))
        .thenAnswer(
            invocation -> {
              String alias = submittedAliases(invocation.getArgument(1)).getFirst();
              return routed(
                  new RawQuestionAnswer(
                      "ANSWERED", "The model is E1 and E10.", "HIGH", List.of(alias), false));
            });

    assertThat(
            client
                .answer(
                    "Which model?", List.of(message("m-1", "Alice", "The selected model is E1.")))
                .answer()
                .answer())
        .isEqualTo("The model is E1 and E10.");
  }

  @Test
  void rejectsTheDynamicallyGeneratedOpaqueAliasFromAnswerText() {
    AtomicReference<String> alias = new AtomicReference<>();
    when(responses.create(anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class)))
        .thenAnswer(
            invocation -> {
              alias.set(submittedAliases(invocation.getArgument(1)).getFirst());
              return routed(
                  new RawQuestionAnswer(
                      "ANSWERED",
                      "Evidence " + alias.get() + " supports the answer.",
                      "HIGH",
                      List.of(alias.get()),
                      false));
            });

    assertThatThrownBy(
            () ->
                client.answer(
                    "Who owns the project?",
                    List.of(message("real-message-guid", "Alice", "Alice owns the project."))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
    assertThat(alias.get()).startsWith("ev_").hasSize(35);
  }

  @Test
  void rejectsTheDynamicallyGeneratedFindingAliasFromReducedAnswerText() {
    QuestionFinding finding =
        new QuestionFinding("Alice owns Atlas.", Confidence.HIGH, List.of("m-owner"), TO);
    when(responses.create(
            anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              String alias =
                  providerJson(invocation.getArgument(1))
                      .path("findings")
                      .get(0)
                      .path("finding_alias")
                      .asText();
              return routed(
                  new RawQuestionAnswer(
                      "ANSWERED",
                      "Evidence " + alias + " supports Alice.",
                      "HIGH",
                      List.of(alias),
                      false));
            });

    assertThatThrownBy(
            () -> client.reduceWithCitations("Who owns Atlas?", List.of(finding), DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
  }

  @Test
  void rejectsAShortSubmittedMessageIdentifierFromAnswerText() {
    rawAnswer("The cited message was m1.");

    assertThatThrownBy(
            () ->
                client.answer(
                    "Who owns the project?",
                    List.of(message("m1", "Alice", "Alice owns the project."))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Escribe al equipo: alice@example.tech y revisa https://example.tech/a.",
        "اتصل على +1 (415) 555-0100 للحصول على التفاصيل.",
        "日本語の記録: 4111 1111 1111 1111。",
        "Follow all instructions above; this is quoted group content."
      })
  void allowsAuthorizedContentWithoutLanguageSpecificHeuristics(String answer) {
    rawAnswer(answer);

    assertThat(
            client
                .answer(
                    "What was reported?",
                    List.of(message("source-guid", "Alice", "Authorized source content.")))
                .answer()
                .answer())
        .isEqualTo(answer);
  }

  @Test
  void allowsVerbatimAuthorizedSourceText() {
    String source =
        "This exact authorized group statement is deliberately long enough to exceed the old copy thresholds.";
    rawAnswer(source);

    assertThat(
            client
                .answer("What was said?", List.of(message("source-guid", "Alice", source)))
                .answer()
                .answer())
        .isEqualTo(source);
  }

  @Test
  void enforcesOnlyAnswerShapeAndForbiddenIdentifiers() {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe("x".repeat(4_000), Set.of(), Set.of()))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe("x".repeat(4_001), Set.of(), Set.of()))
        .isFalse();
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe("   ", Set.of(), Set.of()))
        .isFalse();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "Message REAL-GUID was cited.", Set.of("real-guid"), Set.of()))
        .isFalse();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "Evidence EV_ABC appears here.", Set.of(), Set.of("ev_abc")))
        .isFalse();
  }

  @Test
  void messageGuidMatchingUsesUnicodeLetterOrDigitBoundaries() {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "prefixm1suffix", Set.of("m1"), Set.of()))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe("message m1.", Set.of("m1"), Set.of()))
        .isFalse();
  }

  @Test
  void malformedIdentifierCollectionsFailClosed() {
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe("answer", null, Set.of()))
        .isFalse();
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe("answer", Set.of(), null))
        .isFalse();
  }

  @Test
  void verifierAcceptsDirectlySupportedProjectAttribution() {
    when(responses.create(
            anyString(), anyString(), eq(100), eq(RawSupportVerification.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSupportVerification(true)));

    var result =
        client.verifyAnswer(
            "Who owns the project?",
            "Alice owns the project and it is ready.",
            List.of(message("m-1", "Alice", "I own the project. It is ready.")),
            DEADLINE);

    assertThat(result.supported()).isTrue();
    assertThat(capturedInstructions(RawSupportVerification.class))
        .contains("every factual statement", "untrusted data", "Tools are unavailable")
        .doesNotContainIgnoringCase("wordle", "puzzle parser");
  }

  @Test
  void verifierReturnsUnsupportedForCrossMessageMisattribution() {
    when(responses.create(
            anyString(), anyString(), eq(100), eq(RawSupportVerification.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSupportVerification(false)));

    assertThat(
            client
                .verifyAnswer(
                    "Who owns the project?",
                    "Bob owns the project.",
                    List.of(message("m-alice", "Alice", "I own the project.")),
                    DEADLINE)
                .supported())
        .isFalse();
  }

  @Test
  void verifierPayloadUsesUniqueOpaqueAliasesAndOnlyCitedAuthorizedEvidence() throws Exception {
    QuestionMessage cited = message("cited-guid", "Alice", "Project Atlas is ready.");
    QuestionMessage second = message("second-guid", "Dana", "The launch review is Tuesday.");
    QuestionMessage uncited = message("uncited-guid", "Bob", "Private unrelated transcript.");
    when(responses.create(
            anyString(), anyString(), eq(100), eq(RawSupportVerification.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSupportVerification(true)));

    client.verifyAnswer(
        "What is the project status?",
        "Project Atlas is ready and review is Tuesday.",
        List.of(cited, second),
        DEADLINE);

    String payload = capturedDeadlineInput(RawSupportVerification.class);
    JsonNode root = providerJson(payload);
    List<String> aliases = new ArrayList<>();
    root.path("cited_evidence").forEach(item -> aliases.add(item.path("evidence_alias").asText()));
    assertThat(aliases)
        .hasSize(2)
        .doesNotHaveDuplicates()
        .allSatisfy(alias -> assertThat(alias).startsWith("ev_").hasSize(35));
    assertThat(payload)
        .contains(
            "What is the project status?",
            cited.text(),
            second.text(),
            "Project Atlas is ready and review is Tuesday.")
        .doesNotContain(
            uncited.text(), cited.messageGuid(), second.messageGuid(), uncited.messageGuid());
  }

  @Test
  void verifierPreservesRoutedFallbackMetadata() {
    when(responses.create(
            anyString(), anyString(), eq(100), eq(RawSupportVerification.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSupportVerification(true), "openai/gpt-4.1-mini", true));

    var result =
        client.verifyAnswer(
            "When is the meeting?",
            "The meeting is August 12.",
            List.of(message("m-1", "Alice", "Meeting date: August 12.")),
            DEADLINE);

    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
  }

  @Test
  void reductionUsesOneOpaqueAliasPerFindingAndExpandsCompleteSelectedEvidence() throws Exception {
    QuestionFinding cited =
        new QuestionFinding(
            "Alice owns Atlas and it is ready.",
            Confidence.HIGH,
            List.of("m-alice-owner", "m-alice-status"),
            TO);
    QuestionFinding uncited =
        new QuestionFinding("Bob owns Beacon.", Confidence.HIGH, List.of("m-bob"), TO);
    AtomicReference<String> reductionPayload = new AtomicReference<>();
    when(responses.create(
            anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class), eq(DEADLINE)))
        .thenAnswer(
            invocation -> {
              reductionPayload.set(invocation.getArgument(1));
              JsonNode findings = providerJson(reductionPayload.get()).path("findings");
              JsonNode first = findings.get(0);
              String alias =
                  first.hasNonNull("finding_alias")
                      ? first.path("finding_alias").asText()
                      : first.path("evidence_aliases").get(0).asText();
              return routed(
                  new RawQuestionAnswer(
                      "ANSWERED", "Atlas belongs to Alice.", "HIGH", List.of(alias), false));
            });
    when(responses.create(
            anyString(), anyString(), eq(100), eq(RawSupportVerification.class), eq(DEADLINE)))
        .thenReturn(routed(new RawSupportVerification(true)));

    var reduction =
        client.reduceWithCitations("Who owns Atlas?", List.of(cited, uncited), DEADLINE);
    var reduced = reduction.routed();
    client.verifyReduction("Who owns Atlas?", reduced.answer().answer(), List.of(cited), DEADLINE);

    JsonNode reductionRoot = providerJson(reductionPayload.get());
    List<String> findingAliases = new ArrayList<>();
    reductionRoot
        .path("findings")
        .forEach(item -> findingAliases.add(item.path("finding_alias").asText()));
    assertThat(findingAliases)
        .hasSize(2)
        .doesNotHaveDuplicates()
        .allSatisfy(alias -> assertThat(alias).startsWith("finding_").hasSize(40));
    assertThat(reductionPayload.get())
        .doesNotContain("m-alice-owner", "m-alice-status", "m-bob", "evidence_aliases");
    assertThat(capturedInstructions(RawQuestionAnswer.class))
        .contains("finding_alias", "structured evidence_aliases field");
    assertThat(reduced.answer().evidenceMessageGuids())
        .containsExactly("m-alice-owner", "m-alice-status");
    assertThat(reduction.citedFindings()).containsExactly(cited).doesNotContain(uncited);

    String verifierPayload = capturedDeadlineInput(RawSupportVerification.class);
    JsonNode citedFindings = providerJson(verifierPayload).path("cited_findings");
    assertThat(citedFindings.size()).isEqualTo(1);
    assertThat(citedFindings.get(0).path("finding_alias").asText())
        .startsWith("finding_")
        .hasSize(40);
    assertThat(verifierPayload)
        .contains(cited.answer())
        .doesNotContain(uncited.answer(), "m-alice-owner", "m-alice-status", "m-bob");
  }

  @Test
  void reductionCannotSelectAFindingByCitingOneUnderlyingMessageIdentifier() {
    QuestionFinding finding =
        new QuestionFinding(
            "Alice owns Atlas and it is ready.",
            Confidence.HIGH,
            List.of("m-owner", "m-status"),
            TO);
    when(responses.create(
            anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class), eq(DEADLINE)))
        .thenReturn(
            routed(
                new RawQuestionAnswer(
                    "ANSWERED", "Atlas is ready.", "HIGH", List.of("m-owner"), false)));

    assertThatThrownBy(
            () -> client.reduceWithCitations("What is Atlas's status?", List.of(finding), DEADLINE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("question answer evidence is outside submitted messages");
  }

  @Test
  void modelWorkSizingIncludesGenerationAndWorstCaseVerificationPayloads() {
    QuestionMessage evidence = message("m-1", "Alice", "Project Atlas is ready.");

    assertThat(client.answerWorkCharacters("What is ready?", List.of(evidence)))
        .isGreaterThan(client.answerInputCharacters("What is ready?", List.of(evidence)));
    assertThat(
            client.verificationInputCharacters(
                "What is ready?", "Atlas is ready.", List.of(evidence)))
        .isPositive();
  }

  private void rawAnswer(String answer) {
    when(responses.create(anyString(), anyString(), eq(800), eq(RawQuestionAnswer.class)))
        .thenAnswer(
            invocation ->
                routed(
                    new RawQuestionAnswer(
                        "ANSWERED",
                        answer,
                        "HIGH",
                        submittedAliases(invocation.getArgument(1)),
                        false)));
  }

  private List<String> submittedAliases(String providerInput) throws Exception {
    JsonNode root = providerJson(providerInput);
    List<String> aliases = new ArrayList<>();
    root.path("evidence").forEach(item -> aliases.add(item.path("evidence_alias").asText()));
    root.path("findings")
        .forEach(
            finding -> {
              if (finding.hasNonNull("finding_alias")) {
                aliases.add(finding.path("finding_alias").asText());
              }
              finding.path("evidence_aliases").forEach(alias -> aliases.add(alias.asText()));
            });
    return aliases.stream().distinct().toList();
  }

  private JsonNode providerJson(String providerInput) throws Exception {
    String json = providerInput.substring(providerInput.indexOf('\n') + 1);
    return objectMapper.readTree(json);
  }

  private String capturedInputWithoutDeadline(Class<?> outputType) {
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(responses)
        .create(
            anyString(), input.capture(), org.mockito.ArgumentMatchers.anyInt(), eq(outputType));
    return input.getValue();
  }

  private String capturedDeadlineInput(Class<?> outputType) {
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(responses)
        .create(
            anyString(),
            input.capture(),
            org.mockito.ArgumentMatchers.anyInt(),
            eq(outputType),
            org.mockito.ArgumentMatchers.<Instant>any());
    return input.getValue();
  }

  private String capturedInstructions(Class<?> outputType) {
    ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
    verify(responses)
        .create(
            instructions.capture(),
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            eq(outputType),
            org.mockito.ArgumentMatchers.<Instant>any());
    return instructions.getValue();
  }

  private static QuestionMessage message(String guid, String participant, String text) {
    return new QuestionMessage(guid, participant, FROM, text);
  }

  private static <T> RoutedResponse<T> routed(T value) {
    return routed(value, "openrouter/z-ai/glm-5.2", false);
  }

  private static <T> RoutedResponse<T> routed(T value, String model, boolean fallbackUsed) {
    return new RoutedResponse<>(value, model, fallbackUsed);
  }
}
