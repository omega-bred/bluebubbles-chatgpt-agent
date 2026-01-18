package io.breland.bbagent.server.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingAttachment;
import io.breland.bbagent.server.agent.IncomingMessage;
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

  public static final String GROUP_PREFIX = "iMessage;+;chat";

  @Autowired private BBMessageAgent messageAgent;
  @Autowired private ObjectMapper objectMapper;

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
      // doesn't really matter
      return ResponseEntity.ok(Map.of("status", "ok"));
    }
    IncomingMessage message = parseWebhookMessage(requestBody.getData());
    if (message != null) {
      // TODO: throw on cadence/temporal?
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
    //    String threadOriginatorPart = data.getThreadOriginatorPart();
    //    String replyToGuid = data.getReplyToGuid();
    String text = data.getText();
    Boolean fromMe = data.getIsFromMe();
    String service = data.getHandle().getService();
    String sender = data.getHandle().getAddress();
    Instant timestamp = parseTimestamp(data.getDateCreated());
    List<IncomingAttachment> attachments = parseAttachments(data.getAttachments());
    String chatGuid = data.getChats().getFirst().getGuid();
    Boolean isGroup = resolveIsGroup(data);
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
        attachments);
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

  private boolean resolveIsGroup(BlueBubblesMessageReceivedRequestData data) {
    @NotNull
    @Valid
    List<@Valid BlueBubblesMessageReceivedRequestDataChatsInner> chats = data.getChats();
    if (chats.size() > 1) {
      return true;
    }
    if (data.getGroupTitle() != null && !data.getGroupTitle().isEmpty()) {
      return true;
    }
    if (data.getChats().getFirst().getGuid().startsWith(GROUP_PREFIX)) {
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
