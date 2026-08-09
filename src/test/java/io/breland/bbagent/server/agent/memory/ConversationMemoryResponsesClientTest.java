package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.StructuredResponseOutputItem;
import com.openai.models.responses.StructuredResponseOutputMessage;
import com.openai.services.blocking.ResponseService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

  record TestOutput(String value) {}
}
