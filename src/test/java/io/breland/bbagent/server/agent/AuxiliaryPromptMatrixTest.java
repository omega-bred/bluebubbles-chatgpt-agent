package io.breland.bbagent.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.memory.*;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import io.breland.bbagent.server.agent.tools.giphy.*;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AuxiliaryPromptMatrixTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void materializesMemoryExtractionWindowAndReductionForPrimaryAndFallbackModels()
      throws Exception {
    var client =
        new ConversationMemoryResponsesClient(
            mock(OpenAiClientProvider.class),
            ModelAccessService.STANDARD_RESPONSES_MODEL,
            "openai/gpt-4.1-mini",
            0.4,
            1.6);
    Object[][] tasks = {
      {
        ConversationMemoryModelClient.class,
        "EXTRACTION_INSTRUCTIONS",
        ConversationMemoryModelClient.RawExtractionOutput.class
      },
      {
        ConversationQuestionAnsweringModelClient.class,
        "WINDOW_INSTRUCTIONS",
        ConversationQuestionAnsweringModelClient.RawWindowDecision.class
      },
      {
        ConversationQuestionAnsweringModelClient.class,
        "FINDING_REDUCTION_INSTRUCTIONS",
        ConversationQuestionAnsweringModelClient.RawWindowDecision.class
      }
    };
    for (Object[] task : tasks) {
      String instructions =
          (String) ReflectionTestUtils.getField((Class<?>) task[0], (String) task[1]);
      for (String model :
          List.of(ModelAccessService.STANDARD_RESPONSES_MODEL, "openai/gpt-4.1-mini")) {
        StructuredResponseCreateParams<?> request =
            ReflectionTestUtils.invokeMethod(
                client,
                "buildRequest",
                instructions,
                "Untrusted synthetic review fixture: {}",
                1200,
                task[2],
                model,
                true);
        var params = request.rawParams();
        var role = params.input().orElseThrow().asResponse().getFirst().asEasyInputMessage().role();
        assertThat(role)
            .isEqualTo(
                model.equals(ModelAccessService.STANDARD_RESPONSES_MODEL)
                    ? EasyInputMessage.Role.SYSTEM
                    : EasyInputMessage.Role.DEVELOPER);
        assertThat(params.tools().orElseThrow()).isEmpty();
        assertThat(instructions).contains("untrusted");
        save(task[1] + "-" + (model.startsWith("openrouter/") ? "primary" : "fallback"), params);
      }
    }
  }

  @Test
  void materializesTermsClassifierWithQuotedDataBoundary() throws Exception {
    var provider = mock(OpenAiClientProvider.class);
    var client = mock(OpenAIClient.class);
    var responses = mock(com.openai.services.blocking.ResponseService.class);
    when(provider.get()).thenReturn(client);
    when(client.responses()).thenReturn(responses);
    when(responses.create(any(ResponseCreateParams.class)))
        .thenReturn(mapper.readValue("{\"output\":[]}", Response.class));
    new TermsAgreementValidator(provider, mapper, TermsAgreementValidator.DEFAULT_RESPONSES_MODEL)
        .isHighConfidenceAgreement("Does a reaction to a message count as agreement?");
    var request = ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responses).create(request.capture());
    String instruction =
        request
            .getValue()
            .input()
            .orElseThrow()
            .asResponse()
            .getFirst()
            .asEasyInputMessage()
            .content()
            .asTextInput();
    assertThat(instruction).contains("data to classify", "not itself explicit agreement");
    save("terms", request.getValue());
  }

  @Test
  void materializesGifSelectionWithAndWithoutThumbnailsWithoutNetworkOrSending() throws Exception {
    for (boolean thumbnails : List.of(false, true)) {
      var bb = mock(BBHttpClientWrapper.class);
      var giphy = mock(GiphyClient.class);
      var client = mock(OpenAIClient.class);
      var responses = mock(com.openai.services.blocking.ResponseService.class);
      when(client.responses()).thenReturn(responses);
      when(responses.create(any(ResponseCreateParams.class)))
          .thenReturn(mapper.readValue("{\"output\":[]}", Response.class));
      when(giphy.searchGifs(anyString(), anyInt(), any(), any()))
          .thenReturn(
              List.of(
                  new GiphyClient.GiphyGif(
                      "one",
                      "A friendly wave",
                      "https://example.com/one.gif",
                      thumbnails ? "https://example.com/one.png" : null),
                  new GiphyClient.GiphyGif(
                      "two",
                      "A hello",
                      "https://example.com/two.gif",
                      thumbnails ? "https://example.com/two.png" : null)));
      when(giphy.downloadGifBytes(anyString())).thenReturn(java.util.Optional.empty());
      var incoming =
          new IncomingMessage(
              "chat",
              "message",
              null,
              "Say hello with a GIF",
              false,
              "iMessage",
              "user",
              false,
              Instant.EPOCH,
              List.of(),
              false);
      var outbound = mock(AgentOutboundService.class);
      when(outbound.canSendResponses(null)).thenReturn(true);
      new SendGiphyAgentTool(bb, giphy, () -> client, null)
          .getTool()
          .handler()
          .apply(
              ToolContextFixture.with(incoming).outboundService(outbound).build(),
              mapper.readTree("{\"query\":\"hello\"}"));
      var request = ArgumentCaptor.forClass(ResponseCreateParams.class);
      verify(responses).create(request.capture());
      assertThat(
              request
                  .getValue()
                  .input()
                  .orElseThrow()
                  .asResponse()
                  .getFirst()
                  .asEasyInputMessage()
                  .content()
                  .asTextInput())
          .contains("single integer index from the candidate list", "untrusted data");
      save("gif-thumbnails-" + thumbnails, request.getValue());
      verifyNoInteractions(bb);
    }
  }

  private void save(String name, ResponseCreateParams params) throws Exception {
    Path output = Path.of("build/reports/agent-prompts/auxiliary", name + ".json");
    Files.createDirectories(output.getParent());
    Files.writeString(
        output,
        JsonValue.from(params._body())
            .convert(com.fasterxml.jackson.databind.JsonNode.class)
            .toPrettyString());
  }
}
