package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class BluebubblesWebhookController extends BluebubblesApiController {

  private final BBMessageAgent messageAgent;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public BluebubblesWebhookController(
      NativeWebRequest request,
      BBMessageAgent messageAgent,
      BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.messageAgent = messageAgent;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public ResponseEntity<Object> bluebubblesMessageReceived(
      @Valid @RequestBody BlueBubblesMessageReceivedRequest requestBody) {
    if (requestBody == null || requestBody.getType() == null) {
      return ResponseEntity.badRequest().build();
    }
    if (!isMessageEvent(requestBody)) {
      return ResponseEntity.ok(Map.of("status", "ok"));
    }
    IncomingMessage message = parseWebhookMessage(requestBody.getData());
    if (message != null) {
      log.info("Incoming BlueBubbles message {}", message.logSummary());
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
    String service = data.getHandle() == null ? null : data.getHandle().getService();
    String sender = data.getHandle() == null ? null : data.getHandle().getAddress();
    Instant timestamp = parseTimestamp(data.getDateCreated());
    List<IncomingAttachment> attachments = parseAttachments(data.getAttachments());
    String chatGuid =
        data.getChats() == null || data.getChats().isEmpty()
            ? null
            : data.getChats().getFirst().getGuid();
    boolean isGroup = resolveIsGroup(data, chatGuid);
    // BlueBubbles does not currently provide a reliable system-message signal here.
    boolean isSystem = false;

    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
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
        data.getBalloonBundleId(),
        data.getAssociatedMessageGuid(),
        data.getReplyToGuid(),
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

  private boolean resolveIsGroup(BlueBubblesMessageReceivedRequestData data, String chatGuid) {
    Optional<Boolean> participantResult = resolveIsGroupFromConversationInfo(chatGuid);
    return participantResult.orElseGet(() -> resolveIsGroup(data));
  }

  private Optional<Boolean> resolveIsGroupFromConversationInfo(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return Optional.empty();
    }
    try {
      return resolveIsGroup(bbHttpClientWrapper.getConversationInfo(chatGuid));
    } catch (Exception e) {
      log.warn("Failed to resolve BlueBubbles chat participants for {}", chatGuid, e);
      return Optional.empty();
    }
  }

  public static Optional<Boolean> resolveIsGroup(Chat chat) {
    if (chat == null) {
      return Optional.empty();
    }
    List<ChatParticipant> participants = chat.getParticipants();
    return resolveIsGroupFromParticipantCount(participants == null ? null : participants.size());
  }

  private static Optional<Boolean> resolveIsGroupFromParticipantCount(Integer participantCount) {
    if (participantCount == null || participantCount < 1) {
      return Optional.empty();
    }
    return Optional.of(participantCount > 1);
  }

  public static boolean resolveIsGroup(ApiV1ChatChatGuidMessageGet200ResponseDataInner request) {
    if (request == null) {
      return false;
    }
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner> chats = request.getChats();
    Optional<Boolean> participantResult = resolveIsGroupFromHistoryChatParticipants(chats);
    if (participantResult.isPresent()) {
      return participantResult.get();
    }
    return hasGroupMetadata(chats, request.getGroupTitle());
  }

  private static Optional<Boolean> resolveIsGroupFromHistoryChatParticipants(
      List<ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner> chats) {
    if (chats == null || chats.isEmpty()) {
      return Optional.empty();
    }
    return chats.stream()
        .map(
            chat ->
                resolveIsGroupFromParticipantCount(
                    chat.getParticipants() == null ? null : chat.getParticipants().size()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public static boolean resolveIsGroup(BlueBubblesMessageReceivedRequestData data) {
    if (data == null) {
      return false;
    }
    @NotNull
    @Valid
    List<@Valid BlueBubblesMessageReceivedRequestDataChatsInner> chats = data.getChats();
    return hasGroupMetadata(chats, data.getGroupTitle());
  }

  private static boolean hasGroupMetadata(List<?> chats, String groupTitle) {
    return (chats != null && chats.size() > 1) || (groupTitle != null && !groupTitle.isBlank());
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
