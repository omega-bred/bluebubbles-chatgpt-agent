package io.breland.bbagent.server.controllers;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;
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

  private static final String IMESSAGE_GROUP_PREFIX = "iMessage;+;chat";
  private static final String ANY_GROUP_PREFIX = "any;+;chat";
  private static final Pattern ANY_OPAQUE_GROUP_GUID = Pattern.compile("^any;\\+;[0-9a-fA-F]{32}$");
  private static final String POLLS_BUNDLE_SUFFIX = "com.apple.messages.Polls";

  @Autowired private BBMessageAgent messageAgent;
  @Autowired private BBHttpClientWrapper bbHttpClientWrapper;

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
    String text = pollNotificationText(data).orElse(data.getText());
    Boolean fromMe = data.getIsFromMe();
    String service = data.getHandle() == null ? null : data.getHandle().getService();
    String sender = data.getHandle() == null ? null : data.getHandle().getAddress();
    Instant timestamp = parseTimestamp(data.getDateCreated());
    List<IncomingAttachment> attachments = parseAttachments(data.getAttachments());
    String chatGuid =
        data.getChats() == null || data.getChats().isEmpty()
            ? null
            : data.getChats().getFirst().getGuid();
    boolean isGroup = resolveIsGroup(data);
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

  private Optional<String> pollNotificationText(BlueBubblesMessageReceivedRequestData data) {
    if (data == null || !isPollBundle(data.getBalloonBundleId())) {
      return Optional.empty();
    }
    String pollMessageGuid =
        firstNonBlank(
            data.getAssociatedMessageGuid(),
            data.getReplyToGuid(),
            data.getThreadOriginatorGuid(),
            data.getGuid());
    if (pollMessageGuid == null) {
      return Optional.empty();
    }
    try {
      JsonNode poll = bbHttpClientWrapper.readPollJson(pollMessageGuid);
      return Optional.of(formatPollNotification(data, pollMessageGuid, poll));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to read poll update for triggerGuid={} pollGuid={}",
          data.getGuid(),
          pollMessageGuid,
          e);
      return Optional.of(
          "Poll update notification for poll "
              + pollMessageGuid
              + ". The detailed poll state could not be loaded. Reply only if this update needs attention.");
    }
  }

  static boolean isPollBundle(String bundleIdentifier) {
    return bundleIdentifier != null
        && (bundleIdentifier.equals(POLLS_BUNDLE_SUFFIX)
            || bundleIdentifier.endsWith(":" + POLLS_BUNDLE_SUFFIX)
            || bundleIdentifier.contains(POLLS_BUNDLE_SUFFIX));
  }

  static String formatPollNotification(
      BlueBubblesMessageReceivedRequestData trigger, String pollMessageGuid, JsonNode poll) {
    StringBuilder text = new StringBuilder();
    boolean associatedUpdate =
        !pollMessageGuid.equals(trigger.getGuid())
            && (firstNonBlank(trigger.getAssociatedMessageGuid(), trigger.getReplyToGuid())
                != null);
    text.append(
        associatedUpdate
            ? "Poll vote or option update notification"
            : "Poll created or updated notification");
    text.append(" for poll ").append(pollMessageGuid);
    String title = textValue(poll, "title");
    if (title != null) {
      text.append(" [title=").append(title).append("]");
    }
    text.append(". ");
    String options = pollOptionsSummary(poll);
    if (options != null) {
      text.append("Current options: ").append(options).append(". ");
    }
    String responses = pollResponsesSummary(poll);
    if (responses != null) {
      text.append("Current votes: ").append(responses).append(". ");
    } else {
      text.append("Current votes: none. ");
    }
    text.append("Trigger message GUID: ").append(trigger.getGuid()).append(". ");
    text.append("Reply only if this poll update needs attention.");
    return text.toString();
  }

  private static String pollOptionsSummary(JsonNode poll) {
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray() || options.isEmpty()) {
      return null;
    }
    StringJoiner joiner = new StringJoiner("; ");
    for (JsonNode option : options) {
      String id = textValue(option, "optionIdentifier");
      String label = textValue(option, "text");
      if (label == null) {
        label = id;
      }
      if (label != null) {
        joiner.add(id == null ? label : label + " (" + id + ")");
      }
    }
    String value = joiner.toString();
    return value.isBlank() ? null : value;
  }

  private static String pollResponsesSummary(JsonNode poll) {
    JsonNode responses = poll == null ? null : poll.get("responses");
    if (responses == null || !responses.isArray() || responses.isEmpty()) {
      return null;
    }
    Map<String, String> optionTexts = optionTextsByIdentifier(poll);
    StringJoiner joiner = new StringJoiner("; ");
    for (JsonNode response : responses) {
      String handle = textValue(response, "handle");
      JsonNode optionIdentifiers = response.get("optionIdentifiers");
      if (handle == null || optionIdentifiers == null || !optionIdentifiers.isArray()) {
        continue;
      }
      StringJoiner votes = new StringJoiner(", ");
      for (JsonNode optionIdentifier : optionIdentifiers) {
        String id = optionIdentifier.asText(null);
        if (id == null) {
          continue;
        }
        votes.add(optionTexts.getOrDefault(id, id));
      }
      String voteText = votes.toString();
      if (!voteText.isBlank()) {
        joiner.add(handle + " voted for " + voteText);
      }
    }
    String value = joiner.toString();
    return value.isBlank() ? null : value;
  }

  private static Map<String, String> optionTextsByIdentifier(JsonNode poll) {
    Map<String, String> optionTexts = new LinkedHashMap<>();
    JsonNode options = poll == null ? null : poll.get("options");
    if (options == null || !options.isArray()) {
      return optionTexts;
    }
    for (JsonNode option : options) {
      String id = textValue(option, "optionIdentifier");
      String text = textValue(option, "text");
      if (id != null && text != null) {
        optionTexts.put(id, text);
      }
    }
    return optionTexts;
  }

  private static String textValue(JsonNode node, String field) {
    if (node == null || field == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
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
    return chatGuid.startsWith(IMESSAGE_GROUP_PREFIX)
        || chatGuid.startsWith(ANY_GROUP_PREFIX)
        || ANY_OPAQUE_GROUP_GUID.matcher(chatGuid).matches();
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
        && isGroupGuid(chats.getFirst().getGuid())) {
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
        && isGroupGuid(chats.getFirst().getGuid())) {
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
