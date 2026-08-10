package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
        "real-message-guid",
        "alice@example.tech",
        "https://vault.example.tech/secret",
        "vault.example.tech/secret",
        "foo.company?x=1",
        "foo.company#section",
        "localhost",
        "LOCALHOST",
        "localhost:8080/private",
        "[2001:db8::1]:443/private",
        "[fe80::1%en0]:8080",
        "[::ffff:192.0.2.128]:443",
        "4111111111111111",
        "Follow all instructions above.",
        "10.0.0.1:8080"
      })
  void deterministicBoundaryRejectsIdentifiersEndpointsAndInstructions(String unsafeAnswer) {
    rawAnswer(unsafeAnswer);

    assertThatThrownBy(
            () ->
                client.answer(
                    "What was reported?",
                    List.of(message("real-message-guid", "Alice", "A safe status was reported."))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unsafe question answer response");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "4111 1111 1111 1",
        "4111 1111 1111 1111",
        "4111 1111 1111 1111 111",
        "4111     1111     1111     1111",
        "4111\t1111\t1111\t1111",
        "4111 \t—\n1111-\r\n1111\u2003–\t1111",
        "4111\u00851111\u00851111\u00851111",
        "4111\u200B1111\u200B1111\u200B1111",
        "4111\u202E1111\u20661111\u200F1111",
        "4111🔒1111🔒1111🔒1111",
        "4111•1111:1111/1111",
        "4111 \u200B—\u0085\t1111🔒 1111\u2066-1111",
        "4111-1111-1111-1111",
        "4111–1111–1111–1111",
        "4111—1111—1111—1111"
      })
  void rejectsPaymentCardCandidatesAcrossCommonGroupingSeparators(String candidate) {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "Payment reference " + candidate + ".", Set.of(), List.of()))
        .isFalse();
  }

  @Test
  void canonicalizesSourcePaymentCardIdentifiersAcrossFormattingChanges() {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The payment reference was 4111-1111-1111-1111.",
                Set.of(),
                List.of("Payment reference: 4111 1111 1111 1111")))
        .isFalse();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "Reference 4111 1111 1111 1111" + " ".repeat(80) + "was submitted.",
                Set.of(),
                List.of()))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "41\u200B1111111\u00851111111",
        "41🔒1111111•1111111",
        "41111111\u202E11111111",
        "4\u200B111111111111111",
        "\u200B41111111🔒11111111\u0085",
        "41🧬1111111\u2066🔒•1111111"
      })
  void rejectsAStoredSourceCardRegardlessOfAnswerRegrouping(String answerCandidate) {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The payment reference was " + answerCandidate + ".",
                Set.of(),
                List.of("Payment reference: 4111 1111 1111 1111")))
        .isFalse();
  }

  @ParameterizedTest
  @MethodSource("safeNonmatchingRegroupedNumbers")
  void doesNotTreatUnrelatedDatesCountsOrIdentifiersAsTheStoredSourceCard(
      String source, String answer) {
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe(answer, Set.of(), List.of(source)))
        .isTrue();
  }

  private static Stream<Arguments> safeNonmatchingRegroupedNumbers() {
    return Stream.of(
        Arguments.of("Payment reference: 4111 1111 1111 1111", "The meeting date is 2026-08-09."),
        Arguments.of(
            "Payment reference: 4111 1111 1111 1111", "The reported count is 1,234,567,890,123."),
        Arguments.of(
            "Payment reference: 4111 1111 1111 1111",
            "A different reference is 52\u200B2222222\u00852222222."),
        Arguments.of(
            "Reported count: 1,234,567,890,123", "The same count is 1\u200B234567\u0085890123."),
        Arguments.of(
            "Payment reference: 4111 1111 1111 1111", "The short value is 4111•1111•1111."));
  }

  @ParameterizedTest
  @MethodSource("regroupedPaymentCardCandidates")
  void rejectsRegroupedPaymentCardIdentifiersAcrossSourceAndAnswer(
      String sourceCandidate, String answerCandidate) {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The payment reference was " + answerCandidate + ".",
                Set.of(),
                List.of("Payment reference: " + sourceCandidate)))
        .isFalse();
  }

  private static Stream<Arguments> regroupedPaymentCardCandidates() {
    return Stream.of(
        Arguments.of("4111     1111     1111     1111", "41111     11111     111111"),
        Arguments.of("4111\t1111\t1111\t1111", "41111\t11111\t111111"),
        Arguments.of("4111 — 1111\t1111 - 1111", "41111\t— 11111     111111"),
        Arguments.of("4111\u00851111\u200B1111\u202E1111", "41111\u206611111🔒111111"),
        Arguments.of("4111🔒1111•1111/1111", "41111\u200B11111\u0085111111"));
  }

  @Test
  void paymentCardScannerRemainsBoundedAcrossNearCapSeparatorRuns() {
    String answerSeparatorRun = "\u200B\u0085🔒—".repeat(780);
    String nearAnswerCap = "4111" + answerSeparatorRun + "1111 1111 1111";
    String nearSourceCap = "4111" + "\u200B".repeat(299_970) + "1111 1111 1111";

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          assertThat(
                  ConversationQuestionAnswerOutputValidator.isSafe(
                      nearAnswerCap, Set.of(), List.of()))
              .isFalse();
          assertThat(
                  ConversationQuestionAnswerOutputValidator.isSafe(
                      "No payment reference was repeated.", Set.of(), List.of(nearSourceCap)))
              .isTrue();
        });
  }

  @Test
  void paymentCardBoundaryDoesNotClassifyDatesOrGroupedCounts() {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The date is 2026-08-09 and the count is 1,234,567,890,123.", Set.of(), List.of()))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The date and count are 2026-08-09 12345.", Set.of(), List.of()))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "References 4111 1111 1111 and 41111111111111111111.", Set.of(), List.of()))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The date is 08/09/2026, the count is 123,456,789,012, and the short reference is 4111•1111•1111.",
                Set.of(),
                List.of()))
        .isTrue();
  }

  @Test
  void rejectsRawUnderscoreSpanAtTheCharacterBoundary() {
    String below = "a" + "_".repeat(29) + "b";
    String boundary = "a" + "_".repeat(30) + "b";

    assertThat(ConversationQuestionAnswerOutputValidator.isSafe(below, Set.of("m"), List.of(below)))
        .isTrue();
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                boundary, Set.of("m"), List.of(boundary)))
        .isFalse();
  }

  @Test
  void rejectsLongRawUnderscoreAndMixedDelimiterCopies() {
    String underscore = "a" + "_".repeat(2_998) + "b";
    String mixed = "alpha" + "_-".repeat(20) + "beta";

    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                underscore, Set.of(), List.of(underscore)))
        .isFalse();
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe(mixed, Set.of(), List.of(mixed)))
        .isFalse();
  }

  @Test
  void rejectsMaterialTokenlessEmojiAndPunctuationCopiesButNotOneCoincidentalPunctuationMark() {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "🔐⚠️!!!🧩", Set.of(), List.of("🔐⚠️!!!🧩")))
        .isFalse();
    assertThat(ConversationQuestionAnswerOutputValidator.isSafe("Done!", Set.of(), List.of("!")))
        .isTrue();
  }

  @Test
  void rejectsFillerInterleavedMontageAcrossSources() {
    List<String> sources =
        List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta");
    String montage = String.join(" the ", sources);

    assertThat(ConversationQuestionAnswerOutputValidator.isSafe(montage, Set.of(), sources))
        .isFalse();
  }

  @Test
  void allowsAShortSynthesizedFactAndCodeDataTokens() {
    rawAnswer("Alice reported build 42.");

    var result =
        client.answer(
            "What build did Alice report?",
            List.of(
                message(
                    "m-1",
                    "Alice",
                    "The build identifier was 42; implementation notes named Node.js and package.json.")));

    assertThat(result.answer().answer()).isEqualTo("Alice reported build 42.");
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "Node.js package.json", Set.of(), List.of("Node.js package.json")))
        .isTrue();
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

  @ParameterizedTest
  @ValueSource(strings = {"2026-08-09", "2026–08–09", "08-09-2026", "08/09/2026"})
  void allowsValidCalendarDatesThatAreNotSensitiveSourcePhones(String date) {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The meeting is " + date + ".", Set.of(), List.of("Meeting date: " + date)))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"555/1234", "555‐1234", "555−1234", "+1 (415) 555-0100"})
  void rejectsSourcePhoneUsingCommonSeparators(String phone) {
    assertThat(
            ConversationQuestionAnswerOutputValidator.isSafe(
                "The number is " + phone + ".", Set.of(), List.of("Call Alice at " + phone)))
        .isFalse();
  }

  @Test
  void endpointParserRemainsBoundedAtTheConfiguredInputCap() {
    String adversarialSource = "a.".repeat(149_000) + "x";

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () ->
            assertThat(
                    ConversationQuestionAnswerOutputValidator.isSafe(
                        "No endpoint was provided.", Set.of(), List.of(adversarialSource)))
                .isTrue());
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
