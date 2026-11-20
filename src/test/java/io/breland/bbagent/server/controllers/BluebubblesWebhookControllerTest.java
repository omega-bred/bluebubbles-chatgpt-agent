package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BluebubblesWebhookControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String CHAT_GUID = "any;+;e962a8e9a5624efa87082757a97ac8c1";
  private static final String MESSAGE_GUID = "56CA8540-2C1A-4198-B748-F14238F48783";
  private final BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
  private final BBHttpClientWrapper blueBubbles = Mockito.mock(BBHttpClientWrapper.class);
  private final BluebubblesWebhookController controller =
      new BluebubblesWebhookController(null, agent, blueBubbles, MAPPER);

  @Test
  void forwardsOnlyAuthenticatedMessageContentsAndParticipants() {
    var notification = webhookRequest(CHAT_GUID);
    notification.getData().setText("Webhook text");
    notification.getData().setGroupTitle("Webhook group title");
    notification.getData().setDateCreated(1000L);
    var stored =
        storedMessage(CHAT_GUID)
            .handle(Map.of("address", "sender@example.test", "service", "iMessage"))
            .attachments(List.of(Map.of("guid", "verified-attachment", "mimeType", "image/png")))
            .threadOriginatorGuid("verified-thread")
            .replyToGuid("verified-reply");
    when(blueBubbles.getMessage(MESSAGE_GUID)).thenReturn(stored);

    assertEquals(200, controller.bluebubblesMessageReceived(notification).getStatusCode().value());

    var capture = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(agent).handleIncomingMessage(capture.capture());
    IncomingMessage forwarded = capture.getValue();
    assertTrue(forwarded.verifiedBlueBubblesInbound());
    assertFalse(forwarded.isGroup());
    assertEquals("sender@example.test", forwarded.sender());
    assertEquals("hello", forwarded.text());
    assertEquals(MESSAGE_GUID, forwarded.messageGuid());
    assertEquals("verified-thread", forwarded.threadOriginatorGuid());
    assertEquals("verified-reply", forwarded.replyToGuid());
    assertEquals("verified-attachment", forwarded.attachments().getFirst().guid());
    assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), forwarded.timestamp());
  }

  @Test
  void usesAuthenticatedParticipantLookupWhenMessageDoesNotIncludeParticipants() {
    when(blueBubbles.getMessage(MESSAGE_GUID))
        .thenReturn(storedMessage(CHAT_GUID).chats(List.of(new Chat().guid(CHAT_GUID))));
    when(blueBubbles.getConversationInfo(CHAT_GUID)).thenReturn(chatWithParticipants(2));
    var notification = webhookRequest(CHAT_GUID);
    notification.setType(BlueBubblesMessageReceivedRequest.TypeEnum.UPDATED_MESSAGE);

    controller.bluebubblesMessageReceived(notification);

    var capture = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(agent).handleIncomingMessage(capture.capture());
    assertTrue(capture.getValue().isGroup());
  }

  @Test
  void rejectsMessagesWithoutAnAuthoritativeChatOrIncomingSender() {
    for (Message stored :
        List.of(
            storedMessage("different-chat"),
            storedMessage(CHAT_GUID).guid(UUID.randomUUID()),
            storedMessage(CHAT_GUID).isFromMe(true),
            storedMessage(CHAT_GUID).isSystemMessage(true),
            storedMessage(CHAT_GUID).handle(Map.of()))) {
      when(blueBubbles.getMessage(MESSAGE_GUID)).thenReturn(stored);
      controller.bluebubblesMessageReceived(webhookRequest(CHAT_GUID));
    }
    verifyNoInteractions(agent);
  }

  @Test
  void doesNotProcessMessageDuringBlueBubblesFailures() {
    when(blueBubbles.getMessage(MESSAGE_GUID))
        .thenThrow(new IllegalStateException("Transport unavailable"));
    assertEquals(
        503,
        controller.bluebubblesMessageReceived(webhookRequest(CHAT_GUID)).getStatusCode().value());
    verifyNoInteractions(agent);
  }

  @Test
  void resolveIsGroupUsesHistoryParticipantsWhenPresent() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner group =
        historyData("any;+;e962a8e9a5624efa87082757a97ac8c1", 2);
    ApiV1ChatChatGuidMessageGet200ResponseDataInner direct =
        historyData("any;+;e962a8e9a5624efa87082757a97ac8c1", 1);

    assertTrue(BluebubblesWebhookController.resolveIsGroup(group));
    assertFalse(BluebubblesWebhookController.resolveIsGroup(direct));
  }

  @Test
  void recognizesPollBundleIdentifiers() {
    assertTrue(BlueBubblesPollSupport.isPollBundle("com.apple.messages.Polls"));
    assertTrue(
        BlueBubblesPollSupport.isPollBundle(
            "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls"));
    assertFalse(BlueBubblesPollSupport.isPollBundle("com.apple.messages.Handwriting"));
  }

  @Test
  void formatsPollVoteNotificationsWithCurrentVoteState() throws Exception {
    IncomingMessage trigger = pollMessage("vote-message-guid", "poll-message-guid");
    JsonNode poll =
        MAPPER.readTree(
            """
            {
              "messageGuid": "poll-message-guid",
              "title": "Lunch?",
              "options": [
                { "optionIdentifier": "opt-sushi", "text": "Sushi" },
                { "optionIdentifier": "opt-pizza", "text": "Pizza" }
              ],
              "responses": [
                { "handle": "+15555550123", "optionIdentifiers": [ "opt-sushi" ] }
              ]
            }
            """);

    String text = BlueBubblesPollSupport.formatPollNotification(trigger, "poll-message-guid", poll);

    assertTrue(text.startsWith("Poll update: Poll \"Lunch?\" update"));
    assertTrue(text.contains("Poll vote or option update notification"));
    assertTrue(text.contains("Lunch?"));
    assertTrue(text.contains("Sushi"));
    assertTrue(text.contains("+15555550123 voted for Sushi"));
    assertTrue(text.contains("Tally: Sushi 1 vote, Pizza 0 votes"));
    assertTrue(text.contains("Reply with a concise poll update"));
    assertEquals(
        "Poll update: Poll \"Lunch?\" update: +15555550123 voted for Sushi. Tally: Sushi 1 vote, Pizza 0 votes.",
        BlueBubblesPollSupport.fallbackUserVisiblePollNotification(trigger.withText(text)));
  }

  private static IncomingMessage pollMessage(String messageGuid, String associatedMessageGuid) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;chat",
        messageGuid,
        null,
        null,
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        true,
        Instant.now(),
        List.of(),
        "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls",
        associatedMessageGuid,
        null,
        false);
  }

  private static BlueBubblesMessageReceivedRequest webhookRequest(String chatGuid) {
    BlueBubblesMessageReceivedRequestData data = webhookData(chatGuid);
    data.setOriginalROWID(1);
    data.setGuid(MESSAGE_GUID);
    return new BlueBubblesMessageReceivedRequest(
        BlueBubblesMessageReceivedRequest.TypeEnum.NEW_MESSAGE, data);
  }

  private static Message storedMessage(String chatGuid) {
    return new Message()
        .guid(UUID.fromString(MESSAGE_GUID))
        .text("hello")
        .dateCreated(1_700_000_000_000L)
        .isFromMe(false)
        .handle(Map.of("address", "+15555550123", "service", "iMessage"))
        .chats(List.of(chatWithParticipants(1).guid(chatGuid)));
  }

  private static BlueBubblesMessageReceivedRequestData webhookData(String chatGuid) {
    BlueBubblesMessageReceivedRequestDataChatsInner chat =
        new BlueBubblesMessageReceivedRequestDataChatsInner();
    chat.setGuid(chatGuid);

    BlueBubblesMessageReceivedRequestData data = new BlueBubblesMessageReceivedRequestData();
    data.setChats(List.of(chat));
    return data;
  }

  private static ApiV1ChatChatGuidMessageGet200ResponseDataInner historyData(
      String chatGuid, int participantCount) {
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner();
    chat.setGuid(chatGuid);
    chat.setParticipants(objectList(participantCount));

    ApiV1ChatChatGuidMessageGet200ResponseDataInner data =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInner();
    data.setChats(List.of(chat));
    return data;
  }

  private static Chat chatWithParticipants(int participantCount) {
    Chat chat = new Chat();
    List<ChatParticipant> participants = new ArrayList<>();
    for (int index = 0; index < participantCount; index++) {
      participants.add(new ChatParticipant().address("participant-" + index + "@example.com"));
    }
    chat.setParticipants(participants);
    return chat;
  }

  private static List<Object> objectList(int count) {
    List<Object> values = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      values.add(new Object());
    }
    return values;
  }
}
