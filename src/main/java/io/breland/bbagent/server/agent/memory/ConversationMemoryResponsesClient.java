package io.breland.bbagent.server.agent.memory;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponseCreateParams;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
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
      OpenAiClientProvider openAiClientProvider,
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
        openAiClientProvider,
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
    return createInternal(
        instructions, userInput, maxOutputTokens, outputType, null, Function.identity());
  }

  public <T> RoutedResponse<T> create(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      Instant deadline) {
    Objects.requireNonNull(deadline, "deadline");
    return createInternal(
        instructions, userInput, maxOutputTokens, outputType, deadline, Function.identity());
  }

  public <T, R> RoutedResponse<R> createValidated(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      Function<T, R> validator) {
    return createInternal(instructions, userInput, maxOutputTokens, outputType, null, validator);
  }

  public <T, R> RoutedResponse<R> createValidated(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      Instant deadline,
      Function<T, R> validator) {
    Objects.requireNonNull(deadline, "deadline");
    return createInternal(
        instructions, userInput, maxOutputTokens, outputType, deadline, validator);
  }

  private <T, R> RoutedResponse<R> createInternal(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      @Nullable Instant deadline,
      Function<T, R> validator) {
    requireRequestInput(instructions, userInput, maxOutputTokens, outputType);
    Objects.requireNonNull(validator, "validator");
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
          deadline,
          validator);
    } catch (RuntimeException e) {
      logAttemptFailure(primaryModel, false, e);
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
          deadline,
          validator);
    } catch (RuntimeException fallbackFailure) {
      logAttemptFailure(fallbackModel, true, fallbackFailure);
      fallbackFailure.addSuppressed(primaryFailure);
      throw fallbackFailure;
    }
  }

  private static void logAttemptFailure(
      String model, boolean fallbackUsed, RuntimeException failure) {
    log.warn(
        "Conversation memory model attempt failed model={} fallback={} failureType={} detail={}",
        model,
        fallbackUsed,
        OperationalMetricsService.failureType(failure),
        safeFailureDetail(failure));
  }

  static String safeFailureDetail(Throwable failure) {
    String detail = failure.getClass().getSimpleName();
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 12; depth++) {
      if (current instanceof OpenAIServiceException serviceFailure) {
        return "provider_http_" + serviceFailure.statusCode();
      }
      String knownDetail = knownFailureDetail(current.getMessage());
      if (knownDetail != null) {
        detail = knownDetail;
      }
      Throwable next = current.getCause();
      if (next == current) {
        break;
      }
      current = next;
    }
    return detail;
  }

  private static @Nullable String knownFailureDetail(@Nullable String message) {
    if (message == null) {
      return null;
    }
    return switch (message) {
      case "answered window decision has invalid shape" -> "answered_shape";
      case "older-window decision has invalid shape" -> "older_messages_shape";
      case "clarification decision has invalid shape" -> "clarification_shape";
      case "no-answer decision has invalid shape" -> "no_answer_shape";
      case "question answer evidence is outside submitted messages" -> "unknown_evidence";
      case "question window participant is outside submitted messages" -> "unknown_participant";
      case "finding reduction cited an unknown alias" -> "unknown_finding";
      case "finding reduction requested unavailable older messages" -> "unavailable_older_messages";
      case "memory response returned no structured output" -> "missing_structured_output";
      case "invalid question window response" -> "invalid_window_response";
      case "invalid finding reduction response" -> "invalid_reduction_response";
      case "invalid question answer response" -> "invalid_answer_response";
      default -> null;
    };
  }

  private <T, R> RoutedResponse<R> createWithModel(
      String instructions,
      String userInput,
      int maxOutputTokens,
      Class<T> outputType,
      String model,
      boolean applyPriceCeiling,
      boolean fallbackUsed,
      @Nullable Instant deadline,
      Function<T, R> validator) {
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
    return new RoutedResponse<>(validator.apply(output), model, fallbackUsed);
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
