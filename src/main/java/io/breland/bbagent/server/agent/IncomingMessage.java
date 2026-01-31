package io.breland.bbagent.server.agent;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import java.time.Instant;
import java.util.List;

public record IncomingMessage(
    String chatGuid,
    String messageGuid,
    String threadOriginatorGuid,
    String text,
    Boolean fromMe,
    String service,
    String sender,
    Boolean isGroup,
    Instant timestamp,
    List<IncomingAttachment> attachments) {

  public static IncomingMessage create(ApiV1ChatChatGuidMessageGet200ResponseDataInner message) {
    return new IncomingMessage(
        message.getChats().stream().findFirst().get().getGuid(),
        message.getGuid(),
        null,
        message.getText(),
        message.getIsFromMe(),
        BBMessageAgent.IMESSAGE_SERVICE,
        message.getHandle().getAddress(),
        message.getIsServiceMessage(), // wrong
        Instant.ofEpochSecond(message.getDateCreated()),
        List.of());
  }

  public String computeMessageFingerprint() {
    if (this.messageGuid() != null && !this.messageGuid().isBlank()) {
      return "guid:" + this.messageGuid();
    }
    String sender = this.sender() != null ? this.sender() : "";
    String text = this.text() != null ? this.text() : "";
    long timestamp = this.timestamp() != null ? this.timestamp().toEpochMilli() : 0L;
    int attachmentCount = this.attachments() != null ? this.attachments().size() : 0;
    return sender + "|" + text + "|" + timestamp + "|" + attachmentCount;
  }

  public String summaryForHistory() {
    StringBuilder builder = new StringBuilder();
    if (sender != null && !sender.isBlank()) {
      builder.append(sender).append(": ");
    }
    if (text != null && !text.isBlank()) {
      builder.append(text);
    } else {
      builder.append("[no text]");
    }
    if (attachments != null && !attachments.isEmpty()) {
      long imageCount =
          attachments.stream()
              .filter(att -> att.mimeType() != null && att.mimeType().startsWith("image/"))
              .count();
      if (imageCount > 0) {
        builder.append(" [").append(imageCount).append(" image(s)]");
      }
    }
    return builder.toString();
  }
}
