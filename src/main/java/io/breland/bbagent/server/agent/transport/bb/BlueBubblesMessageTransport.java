package io.breland.bbagent.server.agent.transport.bb;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageReactPostRequest;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.ConversationTurn;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BlueBubblesMessageTransport implements MessageTransport {
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public BlueBubblesMessageTransport(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public String id() {
    return IncomingMessage.TRANSPORT_BLUEBUBBLES;
  }

  @Override
  public String displayName() {
    return "iMessage via BlueBubbles";
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

  private void hydrateMessage(
      ConversationState state,
      ApiV1ChatChatGuidMessageGet200ResponseDataInner msg,
      IncomingMessage currentMessage) {
    if (!shouldIncludeHydratedHistoryMessage(msg)
        || isCurrentMessage(msg.getGuid(), currentMessage)) {
      return;
    }
    IncomingMessage hydratedMessage = IncomingMessage.create(msg);
    Instant timestamp =
        hydratedMessage != null && hydratedMessage.timestamp() != null
            ? hydratedMessage.timestamp()
            : Instant.now();
    if (Boolean.TRUE.equals(msg.getIsFromMe())) {
      state.addTurn(ConversationTurn.assistant(msg.getText(), timestamp));
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
    return text != null && !text.isBlank() && !BBMessageAgent.isReactionMessage(text);
  }

  private boolean isCurrentMessage(String hydratedMessageGuid, IncomingMessage currentMessage) {
    return currentMessage != null
        && currentMessage.messageGuid() != null
        && currentMessage.messageGuid().equals(hydratedMessageGuid);
  }

  @Override
  public boolean sendText(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(message.chatGuid());
    request.setMessage(outgoingMessage.text());
    request.setTempGuid(java.util.UUID.randomUUID().toString());
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
    bbHttpClientWrapper.sendTextDirect(request);
    return true;
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
    request.setChatGuid(conversationId);
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

  @Override
  public boolean supportsIMessageFormatting() {
    return true;
  }
}
