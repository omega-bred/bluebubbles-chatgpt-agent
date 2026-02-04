package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.Workflow;
import io.breland.bbagent.server.agent.AgentResponseHelper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    List<ConversationTurn> history = activities.getConversationHistory(message);
    List<ResponseInputItem> inputItems = activities.buildConversationInput(history, message);
    Response response = activities.createResponse(inputItems, message);
    if (response == null) {
      activities.finalizeWorkflow(message, request.workflowContext(), false);
      return;
    }
    boolean sentTextByTool = false;
    boolean sentReactionByTool = false;
    int loops = 0;
    while (loops < MAX_TOOL_LOOPS) {
      List<ResponseFunctionToolCall> toolCalls = AgentResponseHelper.extractFunctionCalls(response);
      if (toolCalls.isEmpty()) {
        break;
      }
      if (toolCalls.stream().anyMatch(call -> SendTextAgentTool.TOOL_NAME.equals(call.name()))) {
        sentTextByTool = true;
      }
      if (toolCalls.stream()
          .anyMatch(call -> SendReactionAgentTool.TOOL_NAME.equals(call.name()))) {
        sentReactionByTool = true;
      }
      List<ResponseInputItem> toolContinuation = new ArrayList<>(inputItems);
      toolContinuation.addAll(AgentResponseHelper.extractToolContextItems(response));
      toolContinuation.addAll(
          activities.executeToolCalls(toolCalls, message, request.workflowContext()));
      response = activities.createResponse(toolContinuation, message);
      inputItems = toolContinuation;
      loops++;
    }
    String assistantText =
        AgentResponseHelper.normalizeAssistantText(
            new ObjectMapper(), AgentResponseHelper.extractResponseText(response));
    ImageSendResult imageResult =
        activities.handleGeneratedImages(
            response, assistantText, message, request.workflowContext());
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
}
