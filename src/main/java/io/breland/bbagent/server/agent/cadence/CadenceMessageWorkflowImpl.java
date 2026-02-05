package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Workflow;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CadenceMessageWorkflowImpl implements CadenceMessageWorkflow {

  private static final int MAX_TOOL_LOOPS = 50;

  private final CadenceAgentActivities activities =
      Workflow.newActivityStub(
          CadenceAgentActivities.class,
          new ActivityOptions.Builder()
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .build());

  @Override
  public void run(CadenceMessageWorkflowRequest request) {
    if (request == null || request.message() == null || request.workflowContext() == null) {
      return;
    }
    log.info("Handling message via cadence: {}", request.workflowContext());
    IncomingMessage message = request.message();
    String inputItemsJson =
        activities.buildConversationInputJson(activities.getConversationHistory(message), message);
    CadenceResponseBundle bundle = activities.createResponseBundle(inputItemsJson, message);
    if (bundle == null) {
      activities.finalizeWorkflow(message, request.workflowContext(), false);
      return;
    }
    boolean sentTextByTool = false;
    boolean sentReactionByTool = false;
    for (int loops = 0; loops < MAX_TOOL_LOOPS; loops++) {
      if (bundle.toolCalls() == null || bundle.toolCalls().isEmpty()) {
        break;
      }
      sentTextByTool =
          sentTextByTool
              || hasToolCall(
                  bundle.toolCalls(),
                  io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool.TOOL_NAME);
      sentReactionByTool =
          sentReactionByTool
              || hasToolCall(
                  bundle.toolCalls(),
                  io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool.TOOL_NAME);
      String toolOutputsJson =
          activities.executeToolCallsJson(bundle.toolCalls(), message, request.workflowContext());
      inputItemsJson =
          mergeJsonArrays(inputItemsJson, bundle.toolContextItemsJson(), toolOutputsJson);
      bundle = activities.createResponseBundle(inputItemsJson, message);
      if (bundle == null) {
        activities.finalizeWorkflow(message, request.workflowContext(), false);
        return;
      }
    }
    String assistantText = bundle.assistantText();
    ImageSendResult imageResult =
        activities.handleGeneratedImages(
            bundle.responseJson(), assistantText, message, request.workflowContext());
    boolean sentImageByMultipart = imageResult.sentImage();
    if (imageResult.captionSent()) {
      sentTextByTool = true;
    }
    Optional<String> parsedReaction =
        io.breland.bbagent.server.agent.AgentResponseHelper.parseReactionText(assistantText);
    if (parsedReaction.isPresent()) {
      if (!sentReactionByTool) {
        activities.sendReaction(message, parsedReaction.get(), request.workflowContext());
      }
      activities.finalizeWorkflow(message, request.workflowContext(), true);
      return;
    }
    String trimmedText = assistantText == null ? "" : assistantText.trim();
    if (!trimmedText.isBlank() && !BBMessageAgent.NO_RESPONSE_TEXT.equalsIgnoreCase(trimmedText)) {
      if (!sentTextByTool && !sentImageByMultipart) {
        activities.sendThreadAwareText(message, trimmedText, request.workflowContext());
      } else {
        activities.recordAssistantTurn(message, trimmedText, request.workflowContext());
      }
    } else if (sentImageByMultipart) {
      activities.recordAssistantTurn(message, "[image]", request.workflowContext());
    }
    activities.finalizeWorkflow(message, request.workflowContext(), true);
  }

  private static boolean hasToolCall(java.util.List<CadenceToolCall> toolCalls, String name) {
    if (toolCalls == null || toolCalls.isEmpty() || name == null) {
      return false;
    }
    for (CadenceToolCall call : toolCalls) {
      if (name.equals(call.name())) {
        return true;
      }
    }
    return false;
  }

  private static String mergeJsonArrays(String baseJson, String... extraJson) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.node.ArrayNode merged = mapper.createArrayNode();
      appendArrayJson(mapper, merged, baseJson);
      if (extraJson != null) {
        for (String json : extraJson) {
          appendArrayJson(mapper, merged, json);
        }
      }
      return mapper.writeValueAsString(merged);
    } catch (Exception e) {
      throw new RuntimeException("Failed to merge response input arrays", e);
    }
  }

  private static void appendArrayJson(
      com.fasterxml.jackson.databind.ObjectMapper mapper,
      com.fasterxml.jackson.databind.node.ArrayNode target,
      String json)
      throws java.io.IOException {
    if (json == null || json.isBlank()) {
      return;
    }
    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
    if (node == null || !node.isArray()) {
      return;
    }
    for (com.fasterxml.jackson.databind.JsonNode item : node) {
      target.add(item);
    }
  }
}
