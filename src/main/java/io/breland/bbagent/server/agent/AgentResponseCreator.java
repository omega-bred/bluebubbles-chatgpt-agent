package io.breland.bbagent.server.agent;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.decisions.DecisionResponseRouter;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class AgentResponseCreator {
  private final ModelPicker modelPicker;
  private final AgentToolRegistry toolRegistry;
  private final LlmProvider llmProvider;
  private final OperationalMetricsService operationalMetricsService;
  private final AgentProfileService profileService;

  @Nullable
  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    Response routed =
        DecisionResponseRouter.route(inputItems, message, workflowContext, toolRegistry);
    if (routed != null) return routed;
    ModelAccessService.ModelAccess modelAccess = modelPicker.resolveModelAccess(message);
    List<ResponseInputItem> withCapabilities = new java.util.ArrayList<>(inputItems);
    withCapabilities.add(
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.DEVELOPER)
                .content(modelCapabilityInstruction(modelAccess, message))
                .build()));
    if (workflowContext != null && workflowContext.decisionRouting().feedback()) {
      withCapabilities.add(
          ResponseInputItem.ofEasyInputMessage(
              EasyInputMessage.builder()
                  .role(EasyInputMessage.Role.DEVELOPER)
                  .content(
                      "The incoming message was classified as substantive product feedback. If it has"
                          + " not already been recorded in this turn, use record_feedback with the user's exact"
                          + " feedback, then continue helping. This hint does not authorize any other action.")
                  .build()));
    }
    List<ResponseInputItem> requestInputItems =
        modelPicker.shouldSquashDeveloperMessagesIntoSystem(message)
            ? ResponseInputMessages.squashDeveloperMessagesIntoSystem(withCapabilities)
            : withCapabilities;
    List<String> preloaded =
        workflowContext == null ? List.of() : workflowContext.decisionRouting().toolNames();
    List<AgentTool> tools =
        preloaded.isEmpty()
            ? toolRegistry.toolsForModel(message, requestInputItems)
            : toolRegistry.toolsForModel(message, requestInputItems, preloaded);
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

  static String modelCapabilityInstruction(
      ModelAccessService.ModelAccess access, IncomingMessage message) {
    boolean images =
        access.premium() && access.supportsImageGeneration() && message.isBlueBubblesTransport();
    boolean web = access.premium() && access.supportsWebSearch();
    return "Built-in capabilities for this request: image_generation="
        + (images ? "available" : "unavailable")
        + "; web_search="
        + (web ? "available" : "unavailable")
        + ". This reflects the selected model and transport now; earlier assistant claims about availability may be stale. "
        + "Built-in tools cannot be discovered with toolSearchTool. Discover other function tools as needed.";
  }

  private void recordLlmCallMetric(
      IncomingMessage message,
      ModelAccessService.ModelAccess modelAccess,
      boolean success,
      @Nullable String failureType,
      long startedNanos) {
    if (startedNanos <= 0L) {
      return;
    }
    if (profileService.isCanaryAccount(message)) {
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

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }
}
