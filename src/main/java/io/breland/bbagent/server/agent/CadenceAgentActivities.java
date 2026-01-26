package io.breland.bbagent.server.agent;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import java.util.List;

public interface CadenceAgentActivities {
  List<ResponseInputItem> buildConversationInput(
      List<ConversationTurn> history, IncomingMessage message);

  void runMessageWorkflow(IncomingMessage message, AgentWorkflowContext workflowContext);

  Response createResponse(List<ResponseInputItem> inputItems, IncomingMessage message);

  List<ResponseInputItem> executeToolCalls(
      List<ResponseFunctionToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext);

  ImageSendResult handleGeneratedImages(
      Response response,
      String assistantText,
      IncomingMessage message,
      AgentWorkflowContext workflowContext);

  boolean sendReaction(
      IncomingMessage message, String reaction, AgentWorkflowContext workflowContext);

  boolean sendThreadAwareText(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext);

  void recordAssistantTurn(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext);

  void finalizeWorkflow(
      IncomingMessage message, AgentWorkflowContext workflowContext, boolean responded);
}
