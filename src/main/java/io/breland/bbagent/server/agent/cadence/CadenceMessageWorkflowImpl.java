package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Workflow;
import io.breland.bbagent.server.agent.AgentResponseHelper;
import io.breland.bbagent.server.agent.AgentToolLoopGuard;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CadenceMessageWorkflowImpl implements CadenceMessageWorkflow {

  private static final int MAX_TOOL_LOOPS = 50;
  private static final int MAX_CONSECUTIVE_BLOCKED_TOOL_LOOPS = 2;
  private static final ObjectMapper JSON = new ObjectMapper();

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
    if (request.scheduledFor() != null) {
      long now = Workflow.currentTimeMillis();
      long scheduledFor = request.scheduledFor().toEpochMilli();
      if (scheduledFor > now) {
        Workflow.sleep(Duration.ofMillis(scheduledFor - now));
      }
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
    int blockedLoops = 0;
    AgentToolLoopGuard toolLoopGuard = AgentToolLoopGuard.standard();
    for (int loops = 0; loops < MAX_TOOL_LOOPS; loops++) {
      if (bundle.toolCalls() == null || bundle.toolCalls().isEmpty()) {
        break;
      }
      sentTextByTool =
          sentTextByTool || hasToolCall(bundle.toolCalls(), SendTextAgentTool.TOOL_NAME);
      sentReactionByTool =
          sentReactionByTool || hasToolCall(bundle.toolCalls(), SendReactionAgentTool.TOOL_NAME);
      List<CadenceToolCall> executableToolCalls = new ArrayList<>();
      List<CadenceToolCall> blockedToolCalls = new ArrayList<>();
      for (CadenceToolCall toolCall : bundle.toolCalls()) {
        if (toolLoopGuard.shouldBlock(toolCall.name(), toolCall.arguments())) {
          blockedToolCalls.add(toolCall);
        } else {
          executableToolCalls.add(toolCall);
        }
      }
      String toolOutputsJson =
          executableToolCalls.isEmpty()
              ? "[]"
              : activities.executeToolCallsJson(
                  executableToolCalls, message, request.workflowContext());
      String blockedOutputsJson =
          blockedToolCalls.isEmpty() ? "[]" : activities.blockedToolCallsJson(blockedToolCalls);
      inputItemsJson =
          mergeJsonArrays(
              inputItemsJson, bundle.toolContextItemsJson(), toolOutputsJson, blockedOutputsJson);
      bundle = activities.createResponseBundle(inputItemsJson, message);
      if (bundle == null) {
        activities.finalizeWorkflow(message, request.workflowContext(), false);
        return;
      }
      boolean onlyBlockedToolCalls = !blockedToolCalls.isEmpty() && executableToolCalls.isEmpty();
      blockedLoops = onlyBlockedToolCalls ? blockedLoops + 1 : 0;
      if (blockedLoops >= MAX_CONSECUTIVE_BLOCKED_TOOL_LOOPS) {
        log.warn(
            "Stopping cadence tool loop after {} consecutive blocked iterations for {}",
            blockedLoops,
            request.workflowContext());
        break;
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
    Optional<String> parsedReaction = AgentResponseHelper.parseReactionText(assistantText);
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

  private static boolean hasToolCall(List<CadenceToolCall> toolCalls, String name) {
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
      ArrayNode merged = JSON.createArrayNode();
      appendArrayJson(merged, baseJson);
      if (extraJson != null) {
        for (String json : extraJson) {
          appendArrayJson(merged, json);
        }
      }
      return JSON.writeValueAsString(merged);
    } catch (Exception e) {
      throw new RuntimeException("Failed to merge response input arrays", e);
    }
  }

  private static void appendArrayJson(ArrayNode target, String json) throws IOException {
    if (json == null || json.isBlank()) {
      return;
    }
    JsonNode node = JSON.readTree(json);
    if (node == null || !node.isArray()) {
      return;
    }
    for (JsonNode item : node) {
      target.add(item);
    }
  }
}
