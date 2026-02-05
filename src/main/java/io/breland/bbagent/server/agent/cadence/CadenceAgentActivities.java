package io.breland.bbagent.server.agent.cadence;

import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import java.util.List;

public interface CadenceAgentActivities {
  String buildConversationInputJson(List<ConversationTurn> history, IncomingMessage message);

  List<ConversationTurn> getConversationHistory(IncomingMessage message);

  CadenceResponseBundle createResponseBundle(String inputItemsJson, IncomingMessage message);

  String executeToolCallsJson(
      List<CadenceToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext);

  ImageSendResult handleGeneratedImages(
      String responseJson,
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
