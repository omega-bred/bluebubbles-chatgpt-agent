package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;
import com.openai.services.blocking.ResponseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationMemoryResponsesClientTest {
  @Test
  void rejectsBlankInputsAndNonPositiveTokenLimits() {
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(() -> null, "primary", "fallback", 0.4, 1.6);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.create(" ", "input", 200, TestOutput.class));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.create("instructions", " ", 200, TestOutput.class));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.create("instructions", "input", 0, TestOutput.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void rejectsResponsesWithoutStructuredOutput() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> response = mock(StructuredResponse.class);
    StructuredResponseOutputItem<TestOutput> outputItem = mock(StructuredResponseOutputItem.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(response.output()).thenReturn(List.of(outputItem));
    when(outputItem.message()).thenReturn(Optional.empty());
    when(responseService.create(any(StructuredResponseCreateParams.class))).thenReturn(response);
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(() -> openAIClient, "primary", "fallback", 0.4, 1.6);

    assertThatIllegalStateException()
        .isThrownBy(() -> client.create("instructions", "input", 200, TestOutput.class))
        .withMessage("memory response returned no structured output");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void appliesPriceGuardToPrimaryOpenRouterRequest() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> response = successfulResponse(new TestOutput("ok"));
    List<StructuredResponseCreateParams<?>> requests = new ArrayList<>();
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(StructuredResponseCreateParams.class)))
        .thenAnswer(
            invocation -> {
              StructuredResponseCreateParams<?> request = invocation.getArgument(0);
              requests.add(request);
              return response;
            });
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6);

    ConversationMemoryResponsesClient.RoutedResponse<TestOutput> result =
        client.create("instructions", "input", 200, TestOutput.class);

    assertThat(result.model()).isEqualTo("openrouter/z-ai/glm-5.2");
    assertThat(result.fallbackUsed()).isFalse();
    assertThat(requests).singleElement();
    var params = requests.getFirst().rawParams();
    assertThat(params.temperature()).contains(0.0);
    assertThat(params.maxOutputTokens()).contains(200L);
    assertThat(params.tools()).contains(List.of());
    assertThat(params.parallelToolCalls()).contains(false);
    List<ResponseInputItem> input = params.input().orElseThrow().asResponse();
    assertThat(input).hasSize(2);
    assertThat(input.getFirst().asEasyInputMessage().role())
        .isEqualTo(EasyInputMessage.Role.DEVELOPER);
    assertThat(input.getFirst().asEasyInputMessage().content().asTextInput())
        .isEqualTo("instructions");
    assertThat(input.get(1).asEasyInputMessage().role()).isEqualTo(EasyInputMessage.Role.USER);
    assertThat(input.get(1).asEasyInputMessage().content().asTextInput()).isEqualTo("input");
    assertThat(params.toString())
        .contains("require_parameters=true")
        .contains("prompt=0.4")
        .contains("completion=1.6");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void retriesOnceWithFallbackWithoutOpenRouterPriceBody() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> fallbackResponse = successfulResponse(new TestOutput("ok"));
    List<StructuredResponseCreateParams<?>> requests = new ArrayList<>();
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(StructuredResponseCreateParams.class)))
        .thenAnswer(
            invocation -> {
              StructuredResponseCreateParams<?> request = invocation.getArgument(0);
              requests.add(request);
              if (requests.size() == 1) {
                throw new IllegalStateException("primary rejected");
              }
              return fallbackResponse;
            });
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6);

    ConversationMemoryResponsesClient.RoutedResponse<TestOutput> result =
        client.create("instructions", "input", 200, TestOutput.class);

    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).toString()).doesNotContain("max_price");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void retriesWithFallbackWhenPrimaryStructuredOutputFailsSemanticValidation() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> primaryResponse = successfulResponse(new TestOutput("invalid"));
    StructuredResponse<TestOutput> fallbackResponse = successfulResponse(new TestOutput("valid"));
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(StructuredResponseCreateParams.class)))
        .thenReturn(primaryResponse, fallbackResponse);
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6);

    ConversationMemoryResponsesClient.RoutedResponse<String> result =
        client.createValidated(
            "instructions",
            "input",
            200,
            TestOutput.class,
            output -> {
              if (!"valid".equals(output.value())) {
                throw new IllegalStateException("invalid semantic output");
              }
              return output.value().toUpperCase();
            });

    assertThat(result.value()).isEqualTo("VALID");
    assertThat(result.model()).isEqualTo("openai/gpt-4.1-mini");
    assertThat(result.fallbackUsed()).isTrue();
    verify(responseService, times(2)).create(any(StructuredResponseCreateParams.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void preservesPrimarySemanticFailureWhenFallbackSemanticValidationAlsoFails() {
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> primaryResponse = successfulResponse(new TestOutput("primary"));
    StructuredResponse<TestOutput> fallbackResponse =
        successfulResponse(new TestOutput("fallback"));
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.create(any(StructuredResponseCreateParams.class)))
        .thenReturn(primaryResponse, fallbackResponse);
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                client.createValidated(
                    "instructions",
                    "input",
                    200,
                    TestOutput.class,
                    output -> {
                      throw new IllegalStateException(output.value() + " invalid");
                    }))
        .withMessage("fallback invalid")
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .singleElement()
                    .extracting(Throwable::getMessage)
                    .isEqualTo("primary invalid"));
  }

  @Test
  void safeAttemptDiagnosticsNeverExposeProviderErrorText() {
    String diagnostic =
        ConversationMemoryResponsesClient.safeFailureDetail(
            new IllegalStateException("SECRET provider response body"));

    assertThat(diagnostic).isEqualTo("IllegalStateException").doesNotContain("SECRET");
  }

  @Test
  void identifiesRejectedAnsweredShapeInSafeAttemptDiagnostics() {
    String diagnostic =
        ConversationMemoryResponsesClient.safeFailureDetail(
            new IllegalStateException(
                "invalid question window response",
                new IllegalArgumentException("answered window decision has invalid shape")));

    assertThat(diagnostic).isEqualTo("answered_shape");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void boundsEachProviderAttemptToTheRemainingOperationDeadlineWithoutSdkRetries() {
    Instant now = Instant.parse("2026-08-09T12:00:00Z");
    MutableClock clock = new MutableClock(now);
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> fallbackResponse = successfulResponse(new TestOutput("ok"));
    List<Duration> requestTimeouts = new ArrayList<>();
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.withOptions(any())).thenReturn(responseService);
    when(responseService.create(
            any(StructuredResponseCreateParams.class), any(RequestOptions.class)))
        .thenAnswer(
            invocation -> {
              RequestOptions options = invocation.getArgument(1);
              requestTimeouts.add(options.getTimeout().request());
              if (requestTimeouts.size() == 1) {
                clock.advance(Duration.ofSeconds(70));
                throw new IllegalStateException("primary rejected");
              }
              return fallbackResponse;
            });
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6, clock);

    ConversationMemoryResponsesClient.RoutedResponse<TestOutput> result =
        client.create("instructions", "input", 200, TestOutput.class, now.plusSeconds(90));

    assertThat(result.fallbackUsed()).isTrue();
    assertThat(requestTimeouts).containsExactly(Duration.ofSeconds(90), Duration.ofSeconds(20));
    ArgumentCaptor<Consumer<ClientOptions.Builder>> modifiers = consumerCaptor();
    verify(responseService, times(2)).withOptions(modifiers.capture());
    for (Consumer<ClientOptions.Builder> modifier : modifiers.getAllValues()) {
      ClientOptions.Builder builder = mock(ClientOptions.Builder.class);
      modifier.accept(builder);
      verify(builder).maxRetries(0);
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void doesNotStartTheApplicationFallbackAfterTheOperationDeadline() {
    Instant now = Instant.parse("2026-08-09T12:00:00Z");
    MutableClock clock = new MutableClock(now);
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.withOptions(any())).thenReturn(responseService);
    when(responseService.create(
            any(StructuredResponseCreateParams.class), any(RequestOptions.class)))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofSeconds(90));
              throw new IllegalStateException("primary timed out");
            });
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6, clock);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                client.create("instructions", "input", 200, TestOutput.class, now.plusSeconds(90)))
        .withMessage("memory response deadline elapsed");
    verify(responseService, times(1))
        .create(any(StructuredResponseCreateParams.class), any(RequestOptions.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void doesNotStartFallbackWhenPrimarySemanticValidationExhaustsTheDeadline() {
    Instant now = Instant.parse("2026-08-09T12:00:00Z");
    MutableClock clock = new MutableClock(now);
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ResponseService responseService = mock(ResponseService.class);
    StructuredResponse<TestOutput> primaryResponse = successfulResponse(new TestOutput("invalid"));
    when(openAIClient.responses()).thenReturn(responseService);
    when(responseService.withOptions(any())).thenReturn(responseService);
    when(responseService.create(
            any(StructuredResponseCreateParams.class), any(RequestOptions.class)))
        .thenReturn(primaryResponse);
    ConversationMemoryResponsesClient client =
        new ConversationMemoryResponsesClient(
            () -> openAIClient, "openrouter/z-ai/glm-5.2", "openai/gpt-4.1-mini", 0.4, 1.6, clock);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                client.createValidated(
                    "instructions",
                    "input",
                    200,
                    TestOutput.class,
                    now.plusSeconds(90),
                    output -> {
                      clock.advance(Duration.ofSeconds(90));
                      throw new IllegalStateException("primary invalid");
                    }))
        .withMessage("memory response deadline elapsed");
    verify(responseService, times(1))
        .create(any(StructuredResponseCreateParams.class), any(RequestOptions.class));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static StructuredResponse<TestOutput> successfulResponse(TestOutput output) {
    StructuredResponse<TestOutput> response = mock(StructuredResponse.class);
    StructuredResponseOutputItem<TestOutput> outputItem = mock(StructuredResponseOutputItem.class);
    StructuredResponseOutputMessage<TestOutput> outputMessage =
        mock(StructuredResponseOutputMessage.class);
    StructuredResponseOutputMessage.Content<TestOutput> outputContent =
        mock(StructuredResponseOutputMessage.Content.class);
    when(response.output()).thenReturn(List.of(outputItem));
    when(outputItem.message()).thenReturn(Optional.of(outputMessage));
    when(outputMessage.content()).thenReturn(List.of(outputContent));
    when(outputContent.outputText()).thenReturn(Optional.of(output));
    return response;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<Consumer<ClientOptions.Builder>> consumerCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Consumer.class);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  record TestOutput(String value) {}
}
