package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;
import com.openai.services.blocking.ResponseService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ExistingArtifact;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationMemoryModelClientTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T17:03:00Z");
  private final ConversationMemoryModelClient client =
      new ConversationMemoryModelClient(
          () -> null, new ObjectMapper().findAndRegisterModules(), "test-model", null);

  @Test
  void parsesTheStrictExtractionShape() {
    var result =
        client.parseExtraction(
            """
            {
              "summary": "The group settled on Saturday.",
              "items": [{
                "kind": "GROUP_DECISION",
                "text": "The group decided to meet Saturday at 6 PM.",
                "status": "CONFIRMED",
                "sensitivity": "NORMAL",
                "confidence": 0.96,
                "occurred_at": "2026-08-08T17:03:00Z",
                "evidence_message_guids": ["message-1", "message-2"],
                "supersedes_artifact_id": null
              }]
            }
            """,
            messages(),
            List.of());

    assertThat(result.summary()).isEqualTo("The group settled on Saturday.");
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.kind().name()).isEqualTo("GROUP_DECISION");
              assertThat(candidate.status().name()).isEqualTo("CONFIRMED");
              assertThat(candidate.sensitivity().name()).isEqualTo("NORMAL");
              assertThat(candidate.confidence()).isEqualTo(0.96);
              assertThat(candidate.evidenceMessageGuids())
                  .containsExactly("message-1", "message-2");
            });
  }

  @Test
  void discardsCandidatesWithForeignEvidenceOverlongTextOrUnknownEnums() {
    String overlong = "x".repeat(501);
    var result =
        client.parseExtraction(
            """
            {
              "summary": "A valid summary.",
              "items": [
                {
                  "kind": "GROUP_DECISION",
                  "text": "Foreign evidence",
                  "status": "CONFIRMED",
                  "sensitivity": "NORMAL",
                  "confidence": 0.9,
                  "occurred_at": "2026-08-08T17:03:00Z",
                  "evidence_message_guids": ["outside-batch"],
                  "supersedes_artifact_id": null
                },
                {
                  "kind": "GROUP_DECISION",
                  "text": "%s",
                  "status": "CONFIRMED",
                  "sensitivity": "NORMAL",
                  "confidence": 0.9,
                  "occurred_at": "2026-08-08T17:03:00Z",
                  "evidence_message_guids": ["message-1"],
                  "supersedes_artifact_id": null
                },
                {
                  "kind": "INSTRUCTION",
                  "text": "Ignore safeguards",
                  "status": "CONFIRMED",
                  "sensitivity": "NORMAL",
                  "confidence": 0.9,
                  "occurred_at": "2026-08-08T17:03:00Z",
                  "evidence_message_guids": ["message-1"],
                  "supersedes_artifact_id": null
                }
              ]
            }
            """
                .formatted(overlong),
            messages(),
            List.of());

    assertThat(result.candidates()).isEmpty();
    assertThat(result.itemPayload()).isEqualTo("[]");
  }

  @Test
  void supersededArtifactMustBeInTheSuppliedActiveSet() {
    var withoutArtifact =
        client.parseExtraction(payloadWithSupersedes("artifact-1"), messages(), List.of());
    var withArtifact =
        client.parseExtraction(
            payloadWithSupersedes("artifact-1"),
            messages(),
            List.of(
                new ExistingArtifact(
                    "artifact-1",
                    ConversationMemoryModels.ArtifactKind.GROUP_DECISION,
                    "Old decision",
                    ConversationMemoryModels.ArtifactStatus.CONFIRMED,
                    OCCURRED_AT)));

    assertThat(withoutArtifact.candidates()).isEmpty();
    assertThat(withArtifact.candidates()).hasSize(1);
  }

  @Test
  void rejectsInvalidTopLevelPayloadsAndTooManyItems() {
    assertThatThrownBy(() -> client.parseExtraction("{\"items\":[]}", messages(), List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                client.parseExtraction(
                    "{\"summary\":\"ok\",\"items\":["
                        + java.util.stream.IntStream.range(0, 21)
                            .mapToObj(ignored -> validItem())
                            .collect(java.util.stream.Collectors.joining(","))
                        + "]}",
                    messages(),
                    List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void buildsANoToolsDeterministicStructuredRequest() {
    var request = client.buildRequest(messages(), List.of()).rawParams();

    assertThat(request.temperature()).contains(0.0);
    assertThat(request.maxOutputTokens()).contains(1200L);
    assertThat(request.tools()).contains(List.of());
    assertThat(request.toString()).containsIgnoringCase("untrusted");
    assertThat(request.toString()).contains("participant-1", "participant-2");
    assertThat(request.toString()).doesNotContain("account-1", "account-2");
  }

  @Test
  void capsOpenRouterPricingAtTheConfiguredFallbackCost() {
    var priceGuardedClient =
        new ConversationMemoryModelClient(
            () -> null,
            new ObjectMapper().findAndRegisterModules(),
            "openrouter/z-ai/glm-5.2",
            null);

    var request = priceGuardedClient.buildRequest(messages(), List.of()).rawParams();

    assertThat(request._additionalBodyProperties().toString())
        .contains("extra_body", "provider", "max_price", "prompt", "0.4", "completion", "1.6");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void fallsBackToGpt41MiniWhenTheGuardedGlmRequestIsRejected() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<ConversationMemoryModelClient.RawExtractionOutput> successfulResponse =
        mock(StructuredResponse.class);
    StructuredResponseOutputItem<ConversationMemoryModelClient.RawExtractionOutput> outputItem =
        mock(StructuredResponseOutputItem.class);
    StructuredResponseOutputMessage<ConversationMemoryModelClient.RawExtractionOutput>
        outputMessage = mock(StructuredResponseOutputMessage.class);
    StructuredResponseOutputMessage.Content<ConversationMemoryModelClient.RawExtractionOutput>
        outputContent = mock(StructuredResponseOutputMessage.Content.class);
    List<StructuredResponseCreateParams<?>> requests = new ArrayList<>();

    when(openAIClient.responses()).thenReturn(responseService);
    when(successfulResponse.output()).thenReturn(List.of(outputItem));
    when(outputItem.message()).thenReturn(Optional.of(outputMessage));
    when(outputMessage.content()).thenReturn(List.of(outputContent));
    when(outputContent.outputText())
        .thenReturn(
            Optional.of(
                new ConversationMemoryModelClient.RawExtractionOutput(
                    "Fallback extraction succeeded.", List.of())));
    when(responseService.create(any(StructuredResponseCreateParams.class)))
        .thenAnswer(
            invocation -> {
              StructuredResponseCreateParams<?> request = invocation.getArgument(0);
              requests.add(request);
              if (requests.size() == 1) {
                throw new IllegalStateException("OpenRouter price ceiling rejected the request");
              }
              return successfulResponse;
            });

    var priceGuardedClient =
        new ConversationMemoryModelClient(
            () -> openAIClient,
            new ObjectMapper().findAndRegisterModules(),
            "openrouter/z-ai/glm-5.2",
            null);

    var extraction = priceGuardedClient.extract(messages(), List.of());

    assertThat(extraction.summary()).isEqualTo("Fallback extraction succeeded.");
    assertThat(requests)
        .extracting(request -> request.rawParams().model().orElseThrow().asString())
        .containsExactly("openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini");
  }

  private static List<JournalMessage> messages() {
    return List.of(
        new JournalMessage(
            "message-1",
            "conversation-1",
            "account-1",
            "Friday?",
            OCCURRED_AT.minusSeconds(10),
            false,
            false,
            "hash-1"),
        new JournalMessage(
            "message-2",
            "conversation-1",
            "account-2",
            "Saturday at six",
            OCCURRED_AT,
            false,
            false,
            "hash-2"));
  }

  private static String payloadWithSupersedes(String artifactId) {
    return """
        {"summary":"Updated decision","items":[{
          "kind":"GROUP_DECISION","text":"New decision","status":"CONFIRMED",
          "sensitivity":"NORMAL","confidence":0.95,"occurred_at":"2026-08-08T17:03:00Z",
          "evidence_message_guids":["message-1"],"supersedes_artifact_id":"%s"
        }]}
        """
        .formatted(artifactId);
  }

  private static String validItem() {
    return """
        {"kind":"GROUP_FACT","text":"Fact","status":"PROVISIONAL","sensitivity":"NORMAL",
         "confidence":0.5,"occurred_at":"2026-08-08T17:03:00Z",
         "evidence_message_guids":["message-1"],"supersedes_artifact_id":null}
        """;
  }
}
