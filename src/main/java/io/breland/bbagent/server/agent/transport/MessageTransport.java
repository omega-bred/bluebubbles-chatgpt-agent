package io.breland.bbagent.server.agent.transport;

import io.breland.bbagent.server.agent.ConversationState;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.List;

public interface MessageTransport {
  String id();

  String displayName();

  default ConversationState hydrateConversationState(
      String conversationId, IncomingMessage currentMessage) {
    return new ConversationState();
  }

  boolean sendText(IncomingMessage message, OutgoingTextMessage outgoingMessage);

  default boolean sendReaction(IncomingMessage message, String reaction) {
    return false;
  }

  default boolean sendReaction(
      IncomingMessage message,
      String conversationId,
      String selectedMessageGuid,
      String reaction,
      Integer partIndex) {
    return sendReaction(message, reaction);
  }

  default boolean sendMultipartMessage(
      String conversationId, String caption, List<BBHttpClientWrapper.AttachmentData> attachments) {
    return false;
  }

  default boolean supportsReactions() {
    return false;
  }

  default boolean supportsThreadReplies() {
    return false;
  }

  default boolean supportsGeneratedImages() {
    return false;
  }

  default boolean supportsIMessageFormatting() {
    return false;
  }
}
