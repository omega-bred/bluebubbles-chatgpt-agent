package io.breland.bbagent.server.agent;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CadenceAgentActivitiesImpl implements CadenceAgentActivities {

  private final BBMessageAgent messageAgent;

  public CadenceAgentActivitiesImpl(BBMessageAgent messageAgent) {
    this.messageAgent = messageAgent;
  }

  @Override
  public List<ResponseInputItem> buildConversationInput(
      List<ConversationTurn> history, IncomingMessage message) {
    return messageAgent.buildConversationInput(history, message);
  }

  @Override
  public void runMessageWorkflow(IncomingMessage message, AgentWorkflowContext workflowContext) {
    messageAgent.runMessageWorkflowForCadence(message, workflowContext);
  }

  @Override
  public Response createResponse(List<ResponseInputItem> inputItems, IncomingMessage message) {
    return messageAgent.createResponse(inputItems, message, null);
  }

  @Override
  public List<ResponseInputItem> executeToolCalls(
      List<ResponseFunctionToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    return messageAgent.executeToolCalls(toolCalls, message, workflowContext);
  }

  @Override
  public ImageSendResult handleGeneratedImages(
      Response response,
      String assistantText,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    List<GeneratedImage> generatedImages = messageAgent.extractGeneratedImages(response);
    if (generatedImages.isEmpty()) {
      return new ImageSendResult(false, false);
    }
    String caption = assistantText;
    if (caption != null) {
      String trimmed = caption.trim();
      if (trimmed.isBlank()
          || BBMessageAgent.NO_RESPONSE_TEXT.equalsIgnoreCase(trimmed)
          || AgentResponseHelper.parseReactionText(trimmed).isPresent()) {
        caption = null;
      }
    }
    List<BBHttpClientWrapper.AttachmentData> attachments = new ArrayList<>();
    for (GeneratedImage image : generatedImages) {
      attachments.add(new BBHttpClientWrapper.AttachmentData(image.filename(), image.bytes()));
    }
    if (!messageAgent.canSendResponses(workflowContext)) {
      return new ImageSendResult(false, false);
    }
    boolean sent =
        messageAgent
            .getBbHttpClientWrapper()
            .sendMultipartMessage(message.chatGuid(), caption, attachments);
    boolean captionSent = sent && caption != null && !caption.isBlank();
    return new ImageSendResult(sent, captionSent);
  }

  @Override
  public boolean sendReaction(
      IncomingMessage message, String reaction, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return false;
    }
    boolean sent = messageAgent.getBbHttpClientWrapper().sendReactionDirect(message, reaction);
    if (sent) {
      recordAssistantTurn(message, "[reaction: " + reaction + "]", workflowContext);
    }
    return sent;
  }

  @Override
  public boolean sendThreadAwareText(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return false;
    }
    messageAgent.sendThreadAwareText(message, text);
    recordAssistantTurn(message, text, workflowContext);
    return true;
  }

  @Override
  public void recordAssistantTurn(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return;
    }
    ConversationState state = messageAgent.getConversations().get(message.chatGuid());
    if (state != null) {
      synchronized (state) {
        state.addTurn(ConversationTurn.assistant(text, Instant.now()));
      }
    }
  }

  @Override
  public void finalizeWorkflow(
      IncomingMessage message, AgentWorkflowContext workflowContext, boolean responded) {
    ConversationState state = messageAgent.getConversations().get(message.chatGuid());
    if (state == null) {
      return;
    }
    synchronized (state) {
      if (responded) {
        Instant timestamp = message.timestamp() != null ? message.timestamp() : Instant.now();
        state.addTurn(ConversationTurn.user(message.summaryForHistory(), timestamp));
      }
      state.setLastProcessedMessageGuid(message.messageGuid());
      state.setLastProcessedMessageFingerprint(message.computeMessageFingerprint());
      messageAgent.updateThreadContext(state, message);
      if (workflowContext != null && workflowContext.workflowId() != null) {
        state.setLastRespondedWorkflowId(workflowContext.workflowId());
      }
    }
  }
}
