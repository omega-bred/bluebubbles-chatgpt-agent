package io.breland.bbagent.server.agent.memory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponseCreateParams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryResponsesClient {
  static final String DEFAULT_PRIMARY_MODEL = "openrouter/z-ai/glm-5.2";
  static final String DEFAULT_FALLBACK_MODEL = "openai/gpt-4.1-mini";
  static final double DEFAULT_MAX_PROMPT_PRICE = 0.40;
  static final double DEFAULT_MAX_COMPLETION_PRICE = 1.60;

  private final Supplier<OpenAIClient> openAiSupplier;
  private final String primaryModel;
  private final String fallbackModel;
  private final double maxPromptPrice;
  private final double maxCompletionPrice;
  private final Clock clock;

  @Autowired
  public ConversationMemoryResponsesClient(
      @Nullable OpenAIClient openAIClient,
      @Value("${bbagent.memory.group.responses-model:" + DEFAULT_PRIMARY_MODEL + "}")
          String primaryModel,
      @Value("${bbagent.memory.group.fallback-responses-model:" + DEFAULT_FALLBACK_MODEL + "}")
          String fallbackModel,
      @Value(
              "${bbagent.memory.group.max-prompt-price-per-million:"
                  + DEFAULT_MAX_PROMPT_PRICE
                  + "}")
          double maxPromptPrice,
      @Value(
              "${bbagent.memory.group.max-completion-price-per-million:"
                  + DEFAULT_MAX_COMPLETION_PRICE
                  + "}")
          double maxCompletionPrice) {
    this(
        () ->
            openAIClient != null
                ? openAIClient
                : OpenAIOkHttpClient.fromEnv()
                    .withOptions(builder -> builder.timeout(Duration.ofSeconds(120))),
        primaryModel,
        fallbackModel,
        maxPromptPrice,
        maxCompletionPrice,
        Clock.systemUTC());
  }

  ConversationMemoryResponsesClient(
      Supplier<OpenAIClient> openAiSupplier,
      String primaryModel,
      String fallbackModel,
      double maxPromptPrice,
      double maxCompletionPrice) {
    this(
        openAiSupplier,
        primaryModel,
        fallbackModel,
        maxPromptPrice,
        maxCompletionPrice,
        Clock.systemUTC());
  }

  ConversationMemoryResponsesClient(
      Supplier<OpenAIClient> openAiSupplier,
      String primaryModel,
      String fallbackModel,
      double maxPromptPrice,
      double maxCompletionPrice,
      Clock clock) {
    this.openAiSupplier = openAiSupplier;
    this.primaryModel = StringUtils.defaultIfBlank(primaryModel, DEFAULT_PRIMARY_MODEL);
    this.fallbackModel = StringUtils.defaultIfBlank(fallbackModel, DEFAULT_FALLBACK_MODEL);
    this.maxPromptPrice = requirePositivePrice(maxPromptPrice, "max prompt price");
    this.maxCompletionPrice = requirePositivePrice(maxCompletionPrice, "max completion price");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public <T> RoutedResponse<T> create(
      String instructions, String userInput, int maxOutputTokens, Class<T> outputType) {
    return createInternal(instructions, userInput, maxOutputTokens, outputType, null);
  }

  public <T> RoutedResponse<T> create(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      Instant deadline) {
    Objects.requireNonNull(deadline, "deadline");
    return createInternal(instructions, userInput, maxOutputTokens, outputType, deadline);
  }

  private <T> RoutedResponse<T> createInternal(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      @Nullable Instant deadline) {
    requireRequestInput(instructions, userInput, maxOutputTokens, outputType);
    RuntimeException primaryFailure;
    try {
      return createWithModel(
          instructions,
          userInput,
          maxOutputTokens,
          outputType,
          primaryModel,
          true,
          false,
          deadline);
    } catch (RuntimeException e) {
      primaryFailure = e;
    }
    if (primaryModel.equals(fallbackModel)) {
      throw primaryFailure;
    }
    try {
      return createWithModel(
          instructions,
          userInput,
          maxOutputTokens,
          outputType,
          fallbackModel,
          false,
          true,
          deadline);
    } catch (RuntimeException fallbackFailure) {
      fallbackFailure.addSuppressed(primaryFailure);
      throw fallbackFailure;
    }
  }

  private <T> RoutedResponse<T> createWithModel(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      String model,
      boolean applyPriceCeiling,
      boolean fallbackUsed,
      @Nullable Instant deadline) {
    var responses = openAiSupplier.get().responses();
    var request =
        buildRequest(
            instructions, userInput, maxOutputTokens, outputType, model, applyPriceCeiling);
    Duration attemptTimeout = deadline == null ? null : remaining(deadline);
    var response =
        attemptTimeout == null
            ? responses.create(request)
            : responses
                .withOptions(builder -> builder.maxRetries(0))
                .create(request, RequestOptions.builder().timeout(attemptTimeout).build());
    T output =
        response.output().stream()
            .flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream())
            .flatMap(content -> content.outputText().stream())
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("memory response returned no structured output"));
    return new RoutedResponse<>(output, model, fallbackUsed);
  }

  private Duration remaining(Instant deadline) {
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      throw new IllegalStateException("memory response deadline elapsed");
    }
    return remaining;
  }

  private <T> StructuredResponseCreateParams<T> buildRequest(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      String model,
      boolean applyPriceCeiling) {
    ResponseCreateParams.Builder requestBuilder =
        ResponseCreateParams.builder()
            .model(model)
            .temperature(0.0)
            .maxOutputTokens(maxOutputTokens)
            .tools(List.of())
            .parallelToolCalls(false)
            .inputOfResponse(
                List.of(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.DEVELOPER)
                            .content(instructions)
                            .build()),
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content(userInput)
                            .build())));
    if (applyPriceCeiling && model.startsWith("openrouter/")) {
      requestBuilder.putAdditionalBodyProperty(
          "extra_body",
          JsonValue.from(
              Map.of(
                  "provider",
                  Map.of(
                      "sort",
                      "price",
                      "require_parameters",
                      true,
                      "max_price",
                      Map.of("prompt", maxPromptPrice, "completion", maxCompletionPrice)))));
    }
    return requestBuilder.text(outputType).build();
  }

  private static void requireRequestInput(
      String instructions, String userInput, int maxOutputTokens, Class<?> outputType) {
    if (StringUtils.isBlank(instructions)) {
      throw new IllegalArgumentException("instructions must not be blank");
    }
    if (StringUtils.isBlank(userInput)) {
      throw new IllegalArgumentException("user input must not be blank");
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("max output tokens must be positive");
    }
    if (outputType == null) {
      throw new IllegalArgumentException("output type must not be null");
    }
  }

  private static double requirePositivePrice(double value, String name) {
    if (!Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(name + " must be a positive finite value");
    }
    return value;
  }

  public record RoutedResponse<T>(T value, String model, boolean fallbackUsed) {}
}
