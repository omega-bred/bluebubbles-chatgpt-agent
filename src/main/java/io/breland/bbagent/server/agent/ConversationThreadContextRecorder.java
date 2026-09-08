package io.breland.bbagent.server.agent;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class ConversationThreadContextRecorder {
  private final AgentAttachmentInputBuilder attachmentInputBuilder;

  public void updateThreadContext(ConversationState state, IncomingMessage message) {
    if (state == null || message == null) {
      return;
    }
    String threadRootGuid = ThreadReplySupport.threadRootGuid(message);
    if (threadRootGuid == null || threadRootGuid.isBlank()) {
      return;
    }
    List<String> imageUrls = attachmentInputBuilder.resolveImageReferences(message);
    String imageMessageGuid = imageUrls.isEmpty() ? null : message.messageGuid();
    ConversationState.ThreadContext existing = state.getThreadContext(threadRootGuid);
    if ((imageUrls == null || imageUrls.isEmpty()) && existing != null) {
      imageUrls = existing.lastImageUrls();
      imageMessageGuid = existing.lastImageMessageGuid();
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
            imageMessageGuid,
            imageUrls);
    state.recordThreadMessage(threadRootGuid, context);
  }
}
