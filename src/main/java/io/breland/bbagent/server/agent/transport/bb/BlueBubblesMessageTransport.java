package io.breland.bbagent.server.agent.transport.bb;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.TimeSupport;
import io.breland.bbagent.server.agent.AssistantMessageText;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class BlueBubblesMessageTransport implements MessageTransport {
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final ConcurrentMap<String, String> activeTypingTurns = new ConcurrentHashMap<>();

  public BlueBubblesMessageTransport(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public String id() {
    return IncomingMessage.TRANSPORT_BLUEBUBBLES;
  }

  @Override
  public String displayName() {
    return "BlueChat";
  }

  @Override
  public void startTyping(IncomingMessage message, String turnToken) {
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid == null || turnToken == null || turnToken.isBlank()) {
      return;
    }
    activeTypingTurns.put(chatGuid, turnToken);
    bbHttpClientWrapper.startTyping(chatGuid);
  }

  @Override
  public void stopTyping(IncomingMessage message, String turnToken) {
    String chatGuid = IncomingMessage.chatGuidOrNull(message);
    if (chatGuid == null || turnToken == null || turnToken.isBlank()) {
      return;
    }
    if (activeTypingTurns.remove(chatGuid, turnToken)) {
      bbHttpClientWrapper.stopTyping(chatGuid);
    }
  }

  @Override
  public ConversationState hydrateConversationState(
      String conversationId, IncomingMessage currentMessage) {
    ConversationState stateToHydrate = new ConversationState();
    try {
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messages =
          bbHttpClientWrapper.getMessagesInChat(conversationId);
      if (messages != null) {
        messages.reversed().forEach(msg -> hydrateMessage(stateToHydrate, msg, currentMessage));
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to hydrate BlueBubbles conversation history", e);
    }
    return stateToHydrate;
  }

  @Override
  public void startSharedTyping(IncomingMessage message) {
    bbHttpClientWrapper.startUntrackedTyping(message.chatGuid());
  }

  @Override
  public void stopSharedTyping(IncomingMessage message, BooleanSupplier stillCurrent) {
    bbHttpClientWrapper.stopTypingWhile(message.chatGuid(), stillCurrent);
  }

  private void hydrateMessage(
      ConversationState state,
      ApiV1ChatChatGuidMessageGet200ResponseDataInner msg,
      IncomingMessage currentMessage) {
    if (!shouldIncludeHydratedHistoryMessage(msg) || isCurrentOrLater(msg, currentMessage)) {
      return;
    }
    IncomingMessage hydratedMessage =
        IncomingMessage.create(msg)
            .withAttachments(
                io.breland.bbagent.server.agent.cadence.models.IncomingAttachment.fromHistory(
                    msg.getAttachments(), bbHttpClientWrapper.getObjectMapper()));
    Instant timestamp =
        hydratedMessage != null && hydratedMessage.timestamp() != null
            ? hydratedMessage.timestamp()
            : Instant.now();
    if (Boolean.TRUE.equals(msg.getIsFromMe())) {
      String text = AssistantMessageText.stripLeadingMessageGuid(hydratedMessage.text());
      state.addTurn(
          ConversationTurn.assistant(
              text == null || text.isBlank() ? "[no text]" : text,
              timestamp,
              hydratedMessage.metadataForHistory()));
    } else if (hydratedMessage != null) {
      state.recordIncomingTurnIfAbsent(hydratedMessage);
    }
  }

  private boolean shouldIncludeHydratedHistoryMessage(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner msg) {
    if (msg == null) {
      return false;
    }
    if (Boolean.TRUE.equals(msg.getIsSystemMessage())
        || Boolean.TRUE.equals(msg.getIsServiceMessage())) {
      return false;
    }
    String text = msg.getText();
    return (text != null && !text.isBlank())
        || (msg.getAttachments() != null && !msg.getAttachments().isEmpty());
  }

  private boolean isCurrentOrLater(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner historyMessage,
      IncomingMessage currentMessage) {
    if (currentMessage == null) return false;
    if (currentMessage.messageGuid() != null
        && currentMessage.messageGuid().equals(historyMessage.getGuid())) return true;
    Instant timestamp = TimeSupport.epochSecondsOrMillis(historyMessage.getDateCreated());
    return timestamp != null
        && currentMessage.timestamp() != null
        && !timestamp.isBefore(currentMessage.timestamp());
  }

  @Override
  public boolean sendText(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    var request = textRequest(message, outgoingMessage);
    return outgoingMessage.deliveryId() == null
        ? bbHttpClientWrapper.sendTextDirect(request)
        : bbHttpClientWrapper.sendTextDirectOnce(request);
  }

  @Override
  public boolean confirmText(
      IncomingMessage message, OutgoingTextMessage outgoingMessage, Instant startedAt) {
    return bbHttpClientWrapper.confirmTextDelivery(
        textRequest(message, outgoingMessage), startedAt);
  }

  private static ApiV1MessageTextPostRequest textRequest(
      IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(message.chatGuid());
    request.setMessage(outgoingMessage.text());
    request.setTempGuid(
        outgoingMessage.deliveryId() == null
            ? java.util.UUID.randomUUID().toString()
            : outgoingMessage.deliveryId());
    if (outgoingMessage.selectedMessageGuid() != null
        && !outgoingMessage.selectedMessageGuid().isBlank()) {
      request.setSelectedMessageGuid(outgoingMessage.selectedMessageGuid());
    }
    if (outgoingMessage.effectId() != null && !outgoingMessage.effectId().isBlank()) {
      request.setEffectId(outgoingMessage.effectId());
    }
    if (outgoingMessage.partIndex() != null) {
      request.setPartIndex(outgoingMessage.partIndex());
    }
    return request;
  }

  @Override
  public boolean sendReaction(IncomingMessage message, String reaction) {
    return bbHttpClientWrapper.sendReactionDirect(message, reaction);
  }

  @Override
  public boolean sendReaction(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex) {
    ApiV1MessageReactPostRequest request = new ApiV1MessageReactPostRequest();
    request.setChatGuid(
        BBHttpClientWrapper.normalizeDirectAnyChatGuid(
            conversationId, message == null ? null : message.service()));
    request.setSelectedMessageGuid(selectedMessageGuid);
    request.setReaction(reaction);
    if (partIndex != null) {
      request.setPartIndex(partIndex);
    }
    return bbHttpClientWrapper.sendReactionDirect(request);
  }

  @Override
  public boolean sendMultipartMessage(
      String conversationId, String caption, List<BBHttpClientWrapper.AttachmentData> attachments) {
    return bbHttpClientWrapper.sendMultipartMessage(conversationId, caption, attachments);
  }

  @Override
  public boolean supportsReactions() {
    return true;
  }

  @Override
  public boolean supportsThreadReplies() {
    return true;
  }

  @Override
  public boolean supportsGeneratedImages() {
    return true;
  }
}
