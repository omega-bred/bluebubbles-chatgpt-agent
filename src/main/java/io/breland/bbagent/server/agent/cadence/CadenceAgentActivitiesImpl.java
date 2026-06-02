package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import io.breland.bbagent.server.agent.*;
import io.breland.bbagent.server.agent.cadence.models.CadenceResponseBundle;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.cadence.models.GeneratedImage;
import io.breland.bbagent.server.agent.cadence.models.ImageSendResult;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.blobstore.BlobStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CadenceAgentActivitiesImpl implements CadenceAgentActivities {

  private final BBMessageAgent messageAgent;
  private final AgentPromptBuilder promptBuilder;
  private final MessageTransportRegistry transportRegistry;
  private final BlobStore blobStore;
  private final GeneratedImageExtractor generatedImageExtractor = new GeneratedImageExtractor();

  public CadenceAgentActivitiesImpl(
      BBMessageAgent messageAgent,
      AgentPromptBuilder promptBuilder,
      MessageTransportRegistry transportRegistry,
      BlobStore blobStore) {
    this.messageAgent = messageAgent;
    this.promptBuilder = promptBuilder;
    this.transportRegistry = transportRegistry;
    this.blobStore = blobStore;
  }

  @Override
  public String buildConversationInputJson(
      List<ConversationTurn> history, IncomingMessage message) {
    try {
      List<ConversationState.PendingIncomingTurn> pendingIncomingTurns =
          pendingIncomingTurns(message);
      return toJson(promptBuilder.buildConversationInput(history, pendingIncomingTurns, message));
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
  public boolean notifyIfMessageResponseLimitExceeded(
      IncomingMessage message, AgentWorkflowContext workflowContext) {
    return messageAgent.notifyIfMessageResponseLimitExceeded(message, workflowContext);
  }

  @Override
  public CadenceResponseBundle createResponseBundle(
      String inputItemsJson, IncomingMessage message, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return null;
    }
    try {
      JsonNode inputNode = messageAgent.getObjectMapper().readTree(inputItemsJson);
      List<ResponseInputItem> inputItems =
          JsonValue.fromJsonNode(inputNode).convert(new TypeReference<>() {});
      var response = messageAgent.createResponse(inputItems, message, workflowContext);
      if (response == null) {
        return null;
      }
      Set<String> imageCallIds = new HashSet<>();
      response.output().stream()
          .filter(ResponseOutputItem::isImageGenerationCall)
          .map(ResponseOutputItem::asImageGenerationCall)
          .forEach(
              imageGenerationCall -> {
                String id = imageGenerationCall.id();
                Optional<String> value = imageGenerationCall.result();
                if (value.isEmpty()) {
                  if (!id.isBlank()) {
                    imageCallIds.add(id);
                  }
                  return;
                }
                String result = value.get();
                if (!id.isBlank()) {
                  imageCallIds.add(id);
                  if (!result.isBlank()) {
                    this.blobStore.storeBlob(message.chatGuid(), id, result);
                  }
                }
              });
      String responseJson = toJsonWithoutImageResults(response, imageCallIds);
      String assistantText =
          AgentResponseHelper.normalizeAssistantText(
              messageAgent.getObjectMapper(), AgentResponseHelper.extractResponseText(response));
      List<ResponseFunctionToolCall> functionCalls =
          AgentResponseHelper.extractFunctionCalls(response);
      List<CadenceToolCall> toolCalls =
          functionCalls.stream()
              .map(call -> new CadenceToolCall(call.callId(), call.name(), call.arguments()))
              .collect(Collectors.toList());
      String toolContextItemsJson =
          toJson(AgentResponseHelper.extractToolContextItems(response, functionCalls));
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
  public String blockedToolCallsJson(List<CadenceToolCall> toolCalls) {
    try {
      List<ResponseInputItem> outputs = new ArrayList<>();
      if (toolCalls != null) {
        for (CadenceToolCall toolCall : toolCalls) {
          outputs.add(AgentResponseHelper.blockedToolCallOutput(toolCall.callId()));
        }
      }
      return toJson(outputs);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize blocked tool call outputs", e);
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
      String hydratedJson = hydrateResponseJson(responseJson, message.chatGuid());
      response =
          messageAgent
              .getObjectMapper()
              .readValue(hydratedJson, com.openai.models.responses.Response.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse response json", e);
    }
    List<GeneratedImage> generatedImages = generatedImageExtractor.extract(response);
    if (generatedImages.isEmpty()) {
      return new ImageSendResult(false, false);
    }
    MessageTransport transport = transportRegistry.resolve(message);
    if (!transport.supportsGeneratedImages()) {
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
    if (!messageAgent.consumeMessageResponseQuota(message, workflowContext)) {
      return new ImageSendResult(false, false, true);
    }
    boolean sent = transport.sendMultipartMessage(message.chatGuid(), caption, attachments);
    boolean captionSent = sent && caption != null && !caption.isBlank();
    return new ImageSendResult(sent, captionSent);
  }

  @Override
  public boolean sendReaction(
      IncomingMessage message, String reaction, AgentWorkflowContext workflowContext) {
    if (!messageAgent.canSendResponses(workflowContext)) {
      return false;
    }
    MessageTransport transport = transportRegistry.resolve(message);
    if (!transport.supportsReactions()) {
      return false;
    }
    if (!messageAgent.consumeMessageResponseQuota(message, workflowContext)) {
      return false;
    }
    boolean sent = transport.sendReaction(message, reaction);
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
    boolean sent = messageAgent.sendThreadAwareText(message, text, workflowContext);
    if (sent) {
      recordAssistantTurn(message, text, workflowContext);
    }
    return sent;
  }

  @Override
  public void recordAssistantTurn(
      IncomingMessage message, String text, AgentWorkflowContext workflowContext) {
    messageAgent.recordAssistantTurnForCurrentMessage(message, text, workflowContext);
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
        messageAgent.recordIncomingTurnsForResponse(state, message);
      }
      state.markIncomingMessageSeen(message);
      messageAgent.updateThreadContext(state, message);
    }
  }

  private List<ConversationState.PendingIncomingTurn> pendingIncomingTurns(
      IncomingMessage message) {
    if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
      return List.of();
    }
    ConversationState state = messageAgent.getConversations().get(message.chatGuid());
    if (state == null) {
      return List.of();
    }
    synchronized (state) {
      return state.pendingIncomingTurns();
    }
  }

  private String toJson(Object value) throws Exception {
    JsonNode node = JsonValue.from(value).convert(JsonNode.class);
    return messageAgent.getObjectMapper().writeValueAsString(node);
  }

  private String toJsonWithoutImageResults(
      com.openai.models.responses.Response response, Set<String> imageCallIds) throws Exception {
    JsonNode node = JsonValue.from(response).convert(JsonNode.class);
    if (imageCallIds == null || imageCallIds.isEmpty()) {
      return messageAgent.getObjectMapper().writeValueAsString(node);
    }
    if (node instanceof ObjectNode objectNode) {
      JsonNode outputNode = objectNode.get("output");
      if (outputNode != null && outputNode.isArray()) {
        for (JsonNode itemNode : outputNode) {
          if (!(itemNode instanceof ObjectNode itemObject)) {
            continue;
          }
          String id = textValue(itemObject.get("id"));
          if (id == null || !imageCallIds.contains(id)) {
            continue;
          }
          itemObject.remove("result");
        }
      }
    }
    return messageAgent.getObjectMapper().writeValueAsString(node);
  }

  private String hydrateResponseJson(String responseJson, String conversationId) throws Exception {
    if (responseJson == null || responseJson.isBlank()) {
      return responseJson;
    }
    JsonNode node = messageAgent.getObjectMapper().readTree(responseJson);
    if (!(node instanceof ObjectNode objectNode)) {
      return responseJson;
    }
    JsonNode outputNode = objectNode.get("output");
    if (outputNode != null && outputNode.isArray()) {
      for (JsonNode itemNode : outputNode) {
        if (!(itemNode instanceof ObjectNode itemObject)) {
          continue;
        }
        String id = textValue(itemObject.get("id"));
        if (id == null || id.isBlank()) {
          continue;
        }
        JsonNode resultNode = itemObject.get("result");
        if (resultNode != null && !resultNode.isNull()) {
          String existing = resultNode.asText();
          if (existing != null && !existing.isBlank()) {
            continue;
          }
        }
        String blob = blobStore.getBlob(conversationId, id);
        if (blob == null || blob.isBlank()) {
          continue;
        }
        itemObject.put("result", blob);
      }
    }
    return messageAgent.getObjectMapper().writeValueAsString(node);
  }

  private static String textValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    return node.asText();
  }
}
