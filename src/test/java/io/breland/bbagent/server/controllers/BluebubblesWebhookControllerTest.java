package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.ChatParticipant;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataHandle;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BluebubblesWebhookControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void resolveIsGroupUsesConversationParticipants() {
    assertEquals(
        Boolean.TRUE,
        BluebubblesWebhookController.resolveIsGroup(chatWithParticipants(2)).orElseThrow());
    assertEquals(
        Boolean.FALSE,
        BluebubblesWebhookController.resolveIsGroup(chatWithParticipants(1)).orElseThrow());
    assertTrue(BluebubblesWebhookController.resolveIsGroup(chatWithParticipants(0)).isEmpty());
  }

  @Test
  void webhookUsesConversationParticipantsForGroupFlag() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    BluebubblesWebhookController controller =
        new BluebubblesWebhookController(null, messageAgent, bbHttpClientWrapper);
    String chatGuid = "any;+;e962a8e9a5624efa87082757a97ac8c1";
    when(bbHttpClientWrapper.getConversationInfo(chatGuid)).thenReturn(chatWithParticipants(2));

    var response = controller.bluebubblesMessageReceived(webhookRequest(chatGuid));

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(messageAgent).handleIncomingMessage(captor.capture());
    verify(bbHttpClientWrapper).getConversationInfo(chatGuid);
    assertTrue(captor.getValue().isGroup());
  }

  @Test
  void webhookTrustsParticipantLookupOverOpaqueGuid() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    BluebubblesWebhookController controller =
        new BluebubblesWebhookController(null, messageAgent, bbHttpClientWrapper);
    String chatGuid = "any;+;e962a8e9a5624efa87082757a97ac8c1";
    when(bbHttpClientWrapper.getConversationInfo(chatGuid)).thenReturn(chatWithParticipants(1));

    controller.bluebubblesMessageReceived(webhookRequest(chatGuid));

    ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(messageAgent).handleIncomingMessage(captor.capture());
    assertFalse(captor.getValue().isGroup());
  }

  @Test
  void webhookFallsBackToWebhookMetadataWhenParticipantLookupFails() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
    BluebubblesWebhookController controller =
        new BluebubblesWebhookController(null, messageAgent, bbHttpClientWrapper);
    String chatGuid = "iMessage;+;chat293505621450166166";
    BlueBubblesMessageReceivedRequest request = webhookRequest(chatGuid);
    request.getData().setGroupTitle("Family");
    when(bbHttpClientWrapper.getConversationInfo(chatGuid)).thenThrow(new RuntimeException("down"));

    controller.bluebubblesMessageReceived(request);

    ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(messageAgent).handleIncomingMessage(captor.capture());
    assertTrue(captor.getValue().isGroup());
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
  void resolveIsGroupNoLongerTreatsOpaqueGuidAsGroupWithoutParticipants() {
    BlueBubblesMessageReceivedRequestData data =
        webhookData("any;+;e962a8e9a5624efa87082757a97ac8c1");

    assertFalse(BluebubblesWebhookController.resolveIsGroup(data));
  }

  @Test
  void resolveIsGroupFallsBackToWebhookGroupMetadata() {
    BlueBubblesMessageReceivedRequestData data = webhookData("any;-;+18035551212");
    data.setGroupTitle("Family");

    assertTrue(BluebubblesWebhookController.resolveIsGroup(data));
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

    assertTrue(text.contains("Poll vote or option update notification"));
    assertTrue(text.contains("Lunch?"));
    assertTrue(text.contains("Sushi"));
    assertTrue(text.contains("+15555550123 voted for Sushi"));
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
    data.setGuid("message-guid");
    data.setText("hello");
    data.setIsFromMe(false);
    data.setDateCreated(1_700_000_000L);
    data.setHandle(
        new BlueBubblesMessageReceivedRequestDataHandle("+15555550123")
            .service(BBMessageAgent.IMESSAGE_SERVICE));
    return new BlueBubblesMessageReceivedRequest(
        BlueBubblesMessageReceivedRequest.TypeEnum.NEW_MESSAGE, data);
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
