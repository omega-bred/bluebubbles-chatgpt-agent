package io.breland.bbagent.server.agent;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.LxmfMessageReceivedRequest;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import java.time.Instant;
import java.util.ArrayList;
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
    boolean isSystemMessage) {

  public static final String TRANSPORT_BLUEBUBBLES = "bluebubbles";
  public static final String TRANSPORT_LXMF = "lxmf";
  private static final String BLUEBUBBLES_GROUP_PREFIX = "iMessage;+;chat";
  private static final String BLUEBUBBLES_ANY_GROUP_PREFIX = "any;+;chat";
  private static final String LXMF_SERVICE = "LXMF";

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
        isSystemMessage);
  }

  public static IncomingMessage fromBlueBubblesHistory(
      ApiV1ChatChatGuidMessageGet200ResponseDataInner message) {
    if (message == null) {
      return null;
    }
    String chatGuid = firstHistoryChatGuid(message.getChats());
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
        isBlueBubblesGroup(message.getChats(), message.getGroupTitle(), chatGuid),
        parseTimestamp(message.getDateCreated()),
        List.of(),
        Boolean.TRUE.equals(message.getIsSystemMessage())
            || Boolean.TRUE.equals(message.getIsServiceMessage()));
  }

  public static IncomingMessage fromBlueBubblesWebhook(BlueBubblesMessageReceivedRequestData data) {
    if (data == null) {
      return null;
    }
    String chatGuid = firstWebhookChatGuid(data.getChats());
    String service = data.getHandle() == null ? null : data.getHandle().getService();
    String sender = data.getHandle() == null ? null : data.getHandle().getAddress();
    return new IncomingMessage(
        TRANSPORT_BLUEBUBBLES,
        chatGuid,
        data.getGuid(),
        data.getThreadOriginatorGuid(),
        data.getText(),
        data.getIsFromMe(),
        service,
        sender,
        isBlueBubblesGroup(data.getChats(), data.getGroupTitle(), chatGuid),
        parseTimestamp(data.getDateCreated()),
        parseWebhookAttachments(data.getAttachments()),
        false);
  }

  public static IncomingMessage fromLxmfWebhook(LxmfMessageReceivedRequest request) {
    if (request == null) {
      return null;
    }
    String sourceHash = normalizeLxmfHash(request.getSourceHash());
    return new IncomingMessage(
        TRANSPORT_LXMF,
        transportPrefix(TRANSPORT_LXMF, sourceHash),
        request.getMessageId(),
        null,
        request.getContent(),
        false,
        LXMF_SERVICE,
        sourceHash,
        false,
        request.getTimestamp() != null ? request.getTimestamp().toInstant() : Instant.now(),
        List.of(),
        false);
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

  private static Instant parseTimestamp(Long value) {
    if (value == null) {
      return Instant.now();
    }
    if (value > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(value);
    }
    return Instant.ofEpochSecond(value);
  }

  private static String firstHistoryChatGuid(
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner> chats) {
    if (chats == null || chats.isEmpty()) {
      return null;
    }
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat = chats.getFirst();
    return chat == null ? null : chat.getGuid();
  }

  private static String firstWebhookChatGuid(
      List<BlueBubblesMessageReceivedRequestDataChatsInner> chats) {
    if (chats == null || chats.isEmpty()) {
      return null;
    }
    BlueBubblesMessageReceivedRequestDataChatsInner chat = chats.getFirst();
    return chat == null ? null : chat.getGuid();
  }

  private static boolean isBlueBubblesGroup(
      List<?> chats, String groupTitle, String firstChatGuid) {
    if (chats != null && chats.size() > 1) {
      return true;
    }
    if (groupTitle != null && !groupTitle.isBlank()) {
      return true;
    }
    return isBlueBubblesGroupGuid(firstChatGuid);
  }

  private static boolean isBlueBubblesGroupGuid(String chatGuid) {
    return chatGuid != null
        && (chatGuid.startsWith(BLUEBUBBLES_GROUP_PREFIX)
            || chatGuid.startsWith(BLUEBUBBLES_ANY_GROUP_PREFIX));
  }

  private static List<IncomingAttachment> parseWebhookAttachments(
      List<BlueBubblesMessageReceivedRequestDataAttachmentsInner> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      return List.of();
    }
    List<IncomingAttachment> parsed = new ArrayList<>();
    for (BlueBubblesMessageReceivedRequestDataAttachmentsInner attachment : attachments) {
      if (attachment == null) {
        continue;
      }
      parsed.add(
          new IncomingAttachment(
              attachment.getGuid(),
              attachment.getMimeType(),
              attachment.getTransferName(),
              null,
              null,
              null));
    }
    return parsed;
  }

  private static String normalizeLxmfHash(String value) {
    return value == null ? null : value.trim().toLowerCase();
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
