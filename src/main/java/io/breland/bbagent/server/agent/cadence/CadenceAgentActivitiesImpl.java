package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.*;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.GeneratedImage;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CadenceAgentActivitiesImpl implements CadenceAgentActivities {

  private final BBMessageAgent messageAgent;
  private final BBHttpClientWrapper httpClient;

  public CadenceAgentActivitiesImpl(BBMessageAgent messageAgent, BBHttpClientWrapper httpClient) {
    this.messageAgent = messageAgent;
    this.httpClient = httpClient;
  }

  @Override
  public String buildConversationInputJson(
      List<ConversationTurn> history, IncomingMessage message) {
    try {
      return toJson(messageAgent.buildConversationInput(history, message));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize conversation input", e);
    }
  }

  @Override
  public List<ConversationTurn> getConversationHistory(IncomingMessage message) {
    if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
      return List.of();
    }
    ConversationState state = messageAgent.getConversations().get(message.chatGuid());
    if (state == null) {
      state =
          messageAgent
              .getConversations()
              .computeIfAbsent(
                  message.chatGuid(), key -> messageAgent.computeConversationState(key, message));
    }
    synchronized (state) {
      return state.history();
    }
  }

  @Override
  public CadenceResponseBundle createResponseBundle(
      String inputItemsJson, IncomingMessage message) {
    try {
      JsonNode inputNode = messageAgent.getObjectMapper().readTree(inputItemsJson);
      List<ResponseInputItem> inputItems =
          JsonValue.fromJsonNode(inputNode)
              .convert(new TypeReference<List<ResponseInputItem>>() {});
      var response = messageAgent.createResponse(inputItems, message, null);
      if (response == null) {
        return null;
      }
      String responseJson = toJson(response);
      String assistantText =
          AgentResponseHelper.normalizeAssistantText(
              messageAgent.getObjectMapper(), AgentResponseHelper.extractResponseText(response));
      List<CadenceToolCall> toolCalls =
          AgentResponseHelper.extractFunctionCalls(response).stream()
              .map(call -> new CadenceToolCall(call.callId(), call.name(), call.arguments()))
              .collect(Collectors.toList());
      String toolContextItemsJson = toJson(AgentResponseHelper.extractToolContextItems(response));
      return new CadenceResponseBundle(
          responseJson, assistantText, toolContextItemsJson, toolCalls);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create response bundle", e);
    }
  }

  @Override
  public String executeToolCallsJson(
      List<CadenceToolCall> toolCalls,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    try {
      List<com.openai.models.responses.ResponseInputItem> outputs = new ArrayList<>();
      for (CadenceToolCall toolCall : toolCalls) {
        com.openai.models.responses.ResponseFunctionToolCall call =
            com.openai.models.responses.ResponseFunctionToolCall.builder()
                .callId(toolCall.callId())
                .name(toolCall.name())
                .arguments(toolCall.arguments())
                .build();
        outputs.add(messageAgent.runToolActivity(call, message, workflowContext));
      }
      return toJson(outputs);
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute tool calls", e);
    }
  }

  @Override
  public ImageSendResult handleGeneratedImages(
      String responseJson,
      String assistantText,
      IncomingMessage message,
      AgentWorkflowContext workflowContext) {
    com.openai.models.responses.Response response;
    try {
      response =
          messageAgent
              .getObjectMapper()
              .readValue(responseJson, com.openai.models.responses.Response.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse response json", e);
    }
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
    boolean sent = this.httpClient.sendMultipartMessage(message.chatGuid(), caption, attachments);
    boolean captionSent = sent && caption != null && !caption.isBlank();
    return new ImageSendResult(sent, captionSent);
  }

  @Override
  public boolean sendReaction(
      IncomingMessage message, String reaction, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return false;
    }
    boolean sent = this.httpClient.sendReactionDirect(message, reaction);
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
    }
  }

  private String toJson(Object value) throws Exception {
    JsonNode node = JsonValue.from(value).convert(JsonNode.class);
    return messageAgent.getObjectMapper().writeValueAsString(node);
  }
}
