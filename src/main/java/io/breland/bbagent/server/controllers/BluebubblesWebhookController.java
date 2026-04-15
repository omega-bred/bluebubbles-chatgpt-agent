package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class BluebubblesWebhookController extends BluebubblesApiController {

  private static final String GROUP_PREFIX = "iMessage;+;chat";
  private static final String GROUP_PREFIX_2 = "any;+;chat";

  @Autowired private BBMessageAgent messageAgent;

  public BluebubblesWebhookController(NativeWebRequest request) {
    super(request);
  }

  @Override
  public ResponseEntity<Object> bluebubblesMessageReceived(
      @Valid @RequestBody BlueBubblesMessageReceivedRequest requestBody) {
    log.info("Incoming Message {}", requestBody);
    if (requestBody == null || requestBody.getType() == null) {
      return ResponseEntity.badRequest().build();
    }
    if (!isMessageEvent(requestBody)) {
      return ResponseEntity.ok(Map.of("status", "ok"));
    }
    IncomingMessage message = parseWebhookMessage(requestBody.getData());
    if (message != null) {
      messageAgent.handleIncomingMessage(message);
    }
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  private IncomingMessage parseWebhookMessage(
      @NotNull @Valid BlueBubblesMessageReceivedRequestData data) {
    if (data == null) {
      return null;
    }
    String messageGuid = data.getGuid();
    String threadOriginatorGuid = data.getThreadOriginatorGuid();
    String text = data.getText();
    Boolean fromMe = data.getIsFromMe();
    String service = data.getHandle().getService();
    String sender = data.getHandle().getAddress();
    Instant timestamp = parseTimestamp(data.getDateCreated());
    List<IncomingAttachment> attachments = parseAttachments(data.getAttachments());
    String chatGuid = data.getChats().getFirst().getGuid();
    boolean isGroup = resolveIsGroup(data);
    // BlueBubbles does not currently provide a reliable system-message signal here.
    boolean isSystem = false;

    return new IncomingMessage(
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
        isSystem);
  }

  private boolean isMessageEvent(BlueBubblesMessageReceivedRequest request) {
    if (BlueBubblesMessageReceivedRequest.TypeEnum.NEW_MESSAGE.equals(request.getType())) {
      return true;
    }
    if (BlueBubblesMessageReceivedRequest.TypeEnum.UPDATED_MESSAGE.equals(request.getType())) {
      return true;
    }
    return false;
  }

  private static boolean isGroupGuid(String chatGuid) {
    return chatGuid.startsWith(GROUP_PREFIX) || chatGuid.startsWith(GROUP_PREFIX_2);
  }

  public static boolean resolveIsGroup(ApiV1ChatChatGuidMessageGet200ResponseDataInner request) {
    if (request == null) {
      return false;
    }
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner> chats = request.getChats();
    if (chats != null && chats.size() > 1) {
      return true;
    }
    if (request.getGroupTitle() != null && !request.getGroupTitle().isEmpty()) {
      return true;
    }
    if (chats != null
        && !chats.isEmpty()
        && chats.getFirst().getGuid() != null
        && chats.getFirst().getGuid().startsWith(GROUP_PREFIX)) {
      return true;
    }
    return false;
  }

  public static boolean resolveIsGroup(BlueBubblesMessageReceivedRequestData data) {
    @NotNull
    @Valid
    List<@Valid BlueBubblesMessageReceivedRequestDataChatsInner> chats = data.getChats();
    if (chats != null && chats.size() > 1) {
      return true;
    }
    if (data.getGroupTitle() != null && !data.getGroupTitle().isEmpty()) {
      return true;
    }
    if (chats != null
        && !chats.isEmpty()
        && chats.getFirst().getGuid() != null
        && chats.getFirst().getGuid().startsWith(GROUP_PREFIX)) {
      return true;
    }
    return false;
  }

  private Instant parseTimestamp(Long value) {
    if (value == null) {
      return Instant.now();
    }
    long epoch = value;
    if (epoch > 1_000_000_000_000L) {
      return Instant.ofEpochMilli(epoch);
    }
    return Instant.ofEpochSecond(epoch);
  }

  private List<IncomingAttachment> parseAttachments(
      @NotNull @Valid
          List<@Valid BlueBubblesMessageReceivedRequestDataAttachmentsInner> attachmentsNode) {
    if (attachmentsNode == null) {
      return List.of();
    }
    List<IncomingAttachment> attachments = new ArrayList<>();
    for (BlueBubblesMessageReceivedRequestDataAttachmentsInner attachmentNode : attachmentsNode) {
      String guid = attachmentNode.getGuid();
      String mimeType = attachmentNode.getMimeType();
      String filename = attachmentNode.getTransferName();
      attachments.add(new IncomingAttachment(guid, mimeType, filename, null, null, null));
    }
    return attachments;
  }
}
