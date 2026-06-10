package io.breland.bbagent.server.agent;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.llm.LlmProvider;
import io.breland.bbagent.server.agent.llm.LlmRequest;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

@Slf4j
final class AgentResponseCreator {
  private final ModelPicker modelPicker;
  private final AgentToolRegistry toolRegistry;
  private final LlmProvider llmProvider;
  private final @Nullable OperationalMetricsService operationalMetricsService;
  private final AgentProfileService profileService;

  AgentResponseCreator(
      ModelPicker modelPicker,
      AgentToolRegistry toolRegistry,
      LlmProvider llmProvider,
      @Nullable OperationalMetricsService operationalMetricsService,
      AgentProfileService profileService) {
    this.modelPicker = modelPicker;
    this.toolRegistry = toolRegistry;
    this.llmProvider = llmProvider;
    this.operationalMetricsService = operationalMetricsService;
    this.profileService = profileService;
  }

  @Nullable
  Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<ResponseInputItem> requestInputItems =
        modelPicker.shouldSquashDeveloperMessagesIntoSystem(message)
            ? ResponseInputMessages.squashDeveloperMessagesIntoSystem(inputItems)
            : inputItems;
    ModelAccessService.ModelAccess modelAccess = modelPicker.resolveModelAccess(message);
    List<AgentTool> tools = toolRegistry.toolsForModel(message, requestInputItems);
    LlmRequest request =
        new LlmRequest(modelAccess, requestInputItems, tools, message, workflowContext);
    long startedNanos = 0L;
    try {
      startedNanos = System.nanoTime();
      log.info(
          "Creating model response chat={} messageGuid={} workflowId={} provider={} model={} inputItems={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          modelAccess.provider(),
          modelAccess.responsesModel(),
          requestInputItems.size());
      log.trace("Final LLM request: {}", request);
      Response response = llmProvider.createResponse(request);
      recordLlmCallMetric(message, modelAccess, true, null, startedNanos);
      log.info(
          "Created model response chat={} messageGuid={} workflowId={} elapsedMs={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          elapsedMillis(startedNanos));
      return response;
    } catch (RuntimeException e) {
      recordLlmCallMetric(
          message, modelAccess, false, OperationalMetricsService.failureType(e), startedNanos);
      log.warn(
          "LLM response failed chat={} messageGuid={} workflowId={} provider={} model={}",
          message.chatGuid(),
          message.messageGuid(),
          workflowContext == null ? null : workflowContext.workflowId(),
          modelAccess.provider(),
          modelAccess.responsesModel(),
          e);
      return null;
    }
  }

  private void recordLlmCallMetric(
      IncomingMessage message,
      ModelAccessService.ModelAccess modelAccess,
      boolean success,
      @Nullable String failureType,
      long startedNanos) {
    if (operationalMetricsService == null || startedNanos <= 0L) {
      return;
    }
    if (isCanaryAccount(message)) {
      return;
    }
    try {
      operationalMetricsService.recordLlmCall(
          message == null ? "unknown" : message.metricTransport(),
          "agent_response",
          modelAccess == null ? "unknown" : modelAccess.provider(),
          modelAccess == null ? "unknown" : modelAccess.responsesModel(),
          success,
          failureType,
          Duration.ofNanos(System.nanoTime() - startedNanos));
    } catch (RuntimeException e) {
      log.warn("Failed to record LLM call metric", e);
    }
  }

  private boolean isCanaryAccount(IncomingMessage message) {
    if (message == null || profileService == null) {
      return false;
    }
    return profileService.isCanaryAccount(message);
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }
}
