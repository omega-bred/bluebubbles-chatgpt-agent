package io.breland.bbagent.server.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.server.TimeSupport;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
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
  private final ObjectMapper mapper;

  public BluebubblesWebhookController(
      NativeWebRequest request,
      BBMessageAgent messageAgent,
      BBHttpClientWrapper bbHttpClientWrapper,
      ObjectMapper mapper) {
    super(request);
    this.messageAgent = messageAgent;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.mapper = mapper;
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
    BlueBubblesMessageReceivedRequestData notification = requestBody.getData();
    UUID messageId = notification == null ? null : messageId(notification.getGuid());
    if (messageId == null) {
      return ResponseEntity.badRequest().build();
    }

    // Only the notification identifiers are used to locate the authenticated record.
    IncomingMessage message;
    try {
      Message stored = bbHttpClientWrapper.getMessage(notification.getGuid());
      message = verifiedMessage(notification, messageId, stored);
    } catch (RuntimeException e) {
      log.warn("Could not verify BlueBubbles inbound message: {}", e.getClass().getSimpleName());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    if (message != null) {
      try {
        messageAgent.handleIncomingMessage(message);
      } catch (org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException e) {
        log.warn("BlueBubbles inbox temporarily unavailable: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
      }
    }
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  private static UUID messageId(String value) {
    if (value == null || value.length() != 36) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private IncomingMessage verifiedMessage(
      BlueBubblesMessageReceivedRequestData notification, UUID messageId, Message stored) {
    if (stored == null
        || !messageId.equals(stored.getGuid())
        || !Boolean.FALSE.equals(stored.getIsFromMe())
        || Boolean.TRUE.equals(stored.getIsSystemMessage())
        || Boolean.TRUE.equals(stored.getIsServiceMessage())
        || stored.getDateCreated() == null
        || stored.getDateCreated() <= 0
        || stored.getChats() == null
        || notification.getChats() == null
        || notification.getChats().isEmpty()
        || notification.getChats().getFirst() == null) {
      return null;
    }
    var handle = mapper.valueToTree(stored.getHandle());
    String sender = StringUtils.trimToNull(handle.path("address").textValue());
    String service = StringUtils.trimToNull(handle.path("service").textValue());
    String notifiedChatGuid = notification.getChats().getFirst().getGuid();
    if (sender == null || service == null || StringUtils.isBlank(notifiedChatGuid)) {
      return null;
    }
    String normalizedChat =
        BBHttpClientWrapper.normalizeDirectAnyChatGuid(notifiedChatGuid, service);
    Chat chat =
        stored.getChats().stream()
            .filter(Objects::nonNull)
            .filter(
                candidate ->
                    normalizedChat.equals(
                        BBHttpClientWrapper.normalizeDirectAnyChatGuid(
                            candidate.getGuid(), service)))
            .findFirst()
            .orElse(null);
    if (chat == null) {
      return null;
    }
    boolean group =
        resolveIsGroup(chat)
            .or(() -> resolveIsGroupFromConversationInfo(chat.getGuid()))
            .orElseGet(() -> hasGroupMetadata(stored.getChats(), stored.getGroupTitle()));
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        chat.getGuid(),
        notification.getGuid(),
        stored.getThreadOriginatorGuid(),
        stored.getText(),
        false,
        service,
        sender,
        group,
        TimeSupport.epochSecondsOrMillisOrNow(stored.getDateCreated()),
        IncomingAttachment.fromHistory(stored.getAttachments(), mapper),
        stored.getBalloonBundleId(),
        stored.getAssociatedMessageGuid(),
        stored.getReplyToGuid(),
        false,
        true);
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

  private Optional<Boolean> resolveIsGroupFromConversationInfo(String chatGuid) {
    if (chatGuid == null || chatGuid.isBlank()) {
      return Optional.empty();
    }
    try {
      return resolveIsGroup(bbHttpClientWrapper.getConversationInfo(chatGuid));
    } catch (Exception e) {
      log.warn("Could not resolve BlueBubbles chat participants: {}", e.getClass().getSimpleName());
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

  private static boolean hasGroupMetadata(List<?> chats, String groupTitle) {
    return (chats != null && chats.size() > 1) || (groupTitle != null && !groupTitle.isBlank());
  }
}
