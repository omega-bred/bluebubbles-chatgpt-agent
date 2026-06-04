package io.breland.bbagent.server.agent;

import java.time.Instant;
import java.util.List;

final class ConversationThreadContextRecorder {
  private final AgentAttachmentInputBuilder attachmentInputBuilder;

  ConversationThreadContextRecorder(AgentAttachmentInputBuilder attachmentInputBuilder) {
    this.attachmentInputBuilder = attachmentInputBuilder;
  }

  void updateThreadContext(ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return;
    }
    String threadRootGuid = ThreadReplySupport.threadRootGuid(message);
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return;
    }
    List<String> imageUrls = attachmentInputBuilder.resolveImageUrls(message);
    ConversationState.ThreadContext existing = state.getThreadContext(threadRootGuid);
    if ((imageUrls == null || imageUrls.isEmpty()) && existing != null) {
      imageUrls = existing.lastImageUrls();
    }
    String timestamp =
        message.timestamp() != null ? message.timestamp().toString() : Instant.now().toString();
    ConversationState.ThreadContext context =
        new ConversationState.ThreadContext(
            threadRootGuid,
            message.messageGuid(),
            message.text(),
            message.sender(),
            timestamp,
            imageUrls);
    state.recordThreadMessage(threadRootGuid, context);
  }
}
