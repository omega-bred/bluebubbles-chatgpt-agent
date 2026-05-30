package io.breland.bbagent.server.agent;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.controllers.BluebubblesWebhookController;
import java.time.Instant;
import java.util.List;

public record IncomingMessage(
    String transport,
    String chatGuid,
    String messageGuid,
    String threadOriginatorGuid,
    String text,
    Boolean fromMe,
    String service,
    String sender,
    boolean isGroup,
    Instant timestamp,
    List<IncomingAttachment> attachments,
    String balloonBundleId,
    String associatedMessageGuid,
    String replyToGuid,
    boolean isSystemMessage) {

  public static final String TRANSPORT_BLUEBUBBLES = "bluebubbles";
  public static final String TRANSPORT_LXMF = "lxmf";
  public static final String METRIC_TRANSPORT_IMESSAGE = "imessage";

  public IncomingMessage(
      String transport,
      String chatGuid,
      String messageGuid,
      String threadOriginatorGuid,
      String text,
      Boolean fromMe,
      String service,
      String sender,
      boolean isGroup,
      Instant timestamp,
      List<IncomingAttachment> attachments,
      boolean isSystemMessage) {
    this(
        transport,
        chatGuid,
        messageGuid,
        threadOriginatorGuid,
        text,
        fromMe,
        service,
        sender,
        isGroup,
        timestamp,
        attachments,
        null,
        null,
        null,
        isSystemMessage);
  }

  public IncomingMessage(
      String chatGuid,
      String messageGuid,
      String threadOriginatorGuid,
      String text,
      Boolean fromMe,
      String service,
      String sender,
      boolean isGroup,
      Instant timestamp,
      List<IncomingAttachment> attachments,
      boolean isSystemMessage) {
    this(
        TRANSPORT_BLUEBUBBLES,
        chatGuid,
        messageGuid,
        threadOriginatorGuid,
        text,
        fromMe,
        service,
        sender,
        isGroup,
        timestamp,
        attachments,
        null,
        null,
        null,
        isSystemMessage);
  }

  public static IncomingMessage create(ApiV1ChatChatGuidMessageGet200ResponseDataInner message) {
    if (message == null) {
      return null;
    }
    String chatGuid =
        message.getChats() == null
            ? null
            : message.getChats().stream().findFirst().map(chat -> chat.getGuid()).orElse(null);
    String sender = message.getHandle() == null ? null : message.getHandle().getAddress();
    return new IncomingMessage(
        TRANSPORT_BLUEBUBBLES,
        chatGuid,
        message.getGuid(),
        null,
        message.getText(),
        message.getIsFromMe(),
        BBMessageAgent.IMESSAGE_SERVICE,
        sender,
        BluebubblesWebhookController.resolveIsGroup(message),
        parseTimestamp(message.getDateCreated()),
        List.of(),
        message.getBalloonBundleId(),
        message.getAssociatedMessageGuid(),
        null,
        (message.getIsSystemMessage() != null && message.getIsSystemMessage())
            || (message.getIsServiceMessage() != null && message.getIsServiceMessage()));
  }

  public static String transportPrefix(String transport, String id) {
    if (id == null || id.isBlank()) {
      return id;
    }
    if (transport == null || transport.isBlank()) {
      return id;
    }
    String prefix = transport + ":";
    if (id.startsWith(prefix)) {
      return id;
    }
    return prefix + id;
  }

  public static String stripTransportPrefix(String id) {
    if (id == null || id.isBlank()) {
      return id;
    }
    int index = id.indexOf(':');
    if (index <= 0 || index >= id.length() - 1) {
      return id;
    }
    return id.substring(index + 1);
  }

  public String transportOrDefault() {
    return transport != null && !transport.isBlank() ? transport : TRANSPORT_BLUEBUBBLES;
  }

  public boolean isBlueBubblesTransport() {
    return TRANSPORT_BLUEBUBBLES.equalsIgnoreCase(transportOrDefault());
  }

  public boolean isLxmfTransport() {
    return TRANSPORT_LXMF.equalsIgnoreCase(transportOrDefault());
  }

  public String metricTransport() {
    if (isLxmfTransport()) {
      return TRANSPORT_LXMF;
    }
    if (isBlueBubblesTransport()) {
      return METRIC_TRANSPORT_IMESSAGE;
    }
    return transportOrDefault();
  }

  public IncomingMessage withText(String newText) {
    return new IncomingMessage(
        transport,
        chatGuid,
        messageGuid,
        threadOriginatorGuid,
        newText,
        fromMe,
        service,
        sender,
        isGroup,
        timestamp,
        attachments,
        balloonBundleId,
        associatedMessageGuid,
        replyToGuid,
        isSystemMessage);
  }

  private static Instant parseTimestamp(Long value) {
    if (value == null) {
      return Instant.now();
    }
    if (value > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(value);
    }
    return Instant.ofEpochSecond(value);
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

  public static String logSummary(IncomingMessage message) {
    return message == null ? "message=null" : message.logSummary();
  }

  public String logSummary() {
    int attachmentCount = attachments != null ? attachments.size() : 0;
    return "transport=%s chatGuid=%s messageGuid=%s threadOriginatorGuid=%s fromMe=%s service=%s isGroup=%s timestamp=%s attachmentCount=%s hasText=%s balloonBundleId=%s associatedMessageGuid=%s replyToGuid=%s isSystemMessage=%s"
        .formatted(
            transportOrDefault(),
            chatGuid,
            messageGuid,
            threadOriginatorGuid,
            fromMe,
            service,
            isGroup,
            timestamp,
            attachmentCount,
            text != null && !text.isBlank(),
            balloonBundleId,
            associatedMessageGuid,
            replyToGuid,
            isSystemMessage);
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
