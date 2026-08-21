package io.breland.bbagent.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.cadence.CadenceIncomingMessageHandler;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class BBMessageAgent {

  public static final int MAX_HISTORY = 50;
  public static final String NO_RESPONSE_TEXT = "NO_RESPONSE";
  public static final String AGENT_PHONE_NUMBER = "+1 (415) 867-4956";
  public static final String IMESSAGE_SERVICE = "iMessage";

  @Getter private final ObjectMapper objectMapper;
  private final ConversationStateStore conversationStateStore;
  private final CadenceIncomingMessageHandler incomingMessageHandler;
  private final AgentToolActivityRunner toolActivityRunner;
  private final AgentOutboundService outboundService;
  private final AgentResponseCreator responseCreator;
  private final ConversationThreadContextRecorder threadContextRecorder;
  private final NativeAppSessionService nativeAppSessionService;

  public Map<String, ConversationState> getConversations() {
    return conversationStateStore.conversations();
  }

  public ConversationState computeConversationState(String chatId, IncomingMessage message) {
    return incomingMessageHandler.computeConversationState(chatId, message);
  }

  // main invocation point from webhook
  public void handleIncomingMessage(IncomingMessage message) {
    incomingMessageHandler.handleIncomingMessage(claimNativeAppStartToken(message));
  }

  private IncomingMessage claimNativeAppStartToken(IncomingMessage message) {
    try {
      return nativeAppSessionService.claimStartToken(message);
    } catch (Exception e) {
      log.warn("native_app_start_token_claim_failed", e);
      return message;
    }
  }

  public boolean canSendResponses(AgentWorkflowContext workflowContext) {
    return outboundService.canSendResponses(workflowContext);
  }

  boolean canSendResponsesForWorkflowRun(
      AgentWorkflowContext workflowContext, @Nullable String currentRunId) {
    return outboundService.canSendResponsesForWorkflowRun(workflowContext, currentRunId);
  }

  public void recordAssistantTurnForCurrentMessage(
      IncomingMessage message, String content, AgentWorkflowContext workflowContext) {
    outboundService.recordAssistantTurn(message, content, workflowContext);
  }

  public void recordIncomingTurnsForResponse(ConversationState state, IncomingMessage message) {
    outboundService.recordIncomingTurnsForResponse(state, message);
  }

  public boolean sendThreadAwareText(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext) {
    return outboundService.sendThreadAwareText(message, text, workflowContext);
  }

  public boolean sendThreadAwareTextUnmetered(IncomingMessage message, String text) {
    return outboundService.sendThreadAwareTextUnmetered(message, text);
  }

  public boolean sendTextUnmetered(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    return outboundService.sendTextUnmetered(message, outgoingMessage);
  }

  public boolean sendTextFromTool(
      IncomingMessage message,
      OutgoingTextMessage outgoingMessage,
      AgentWorkflowContext workflowContext) {
    return outboundService.sendTextFromTool(message, outgoingMessage, workflowContext);
  }

  public boolean sendReactionFromTool(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex,
      AgentWorkflowContext workflowContext) {
    return outboundService.sendReactionFromTool(
        message, conversationId, selectedMessageGuid, reaction, partIndex, workflowContext);
  }

  public boolean notifyIfMessageResponseLimitExceeded(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    return outboundService.notifyIfMessageResponseLimitExceeded(message, workflowContext);
  }

  public boolean consumeMessageResponseQuota(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    return outboundService.consumeMessageResponseQuota(message, workflowContext);
  }

  public Response createResponse(
      List<ResponseInputItem> inputItems,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    return responseCreator.createResponse(inputItems, message, workflowContext);
  }

  public ResponseInputItem runToolActivity(
      ResponseFunctionToolCall toolCall,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    return toolActivityRunner.run(toolCall, message, workflowContext);
  }

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    threadContextRecorder.updateThreadContext(state, message);
  }
}
