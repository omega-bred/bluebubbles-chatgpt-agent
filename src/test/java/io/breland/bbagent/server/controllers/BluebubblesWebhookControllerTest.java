package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BlueBubblesPollSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BluebubblesWebhookControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void resolveIsGroupRecognizesAnyOpaqueGroupGuidFromWebhook() {
    BlueBubblesMessageReceivedRequestData data =
        webhookData("any;+;e962a8e9a5624efa87082757a97ac8c1");

    assertTrue(BluebubblesWebhookController.resolveIsGroup(data));
  }

  @Test
  void resolveIsGroupRecognizesAnyOpaqueGroupGuidFromHistory() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInner data =
        historyData("any;+;e962a8e9a5624efa87082757a97ac8c1");

    assertTrue(BluebubblesWebhookController.resolveIsGroup(data));
  }

  @Test
  void resolveIsGroupKeepsAnyDirectGuidNonGroup() {
    BlueBubblesMessageReceivedRequestData data = webhookData("any;-;+18035551212");

    assertFalse(BluebubblesWebhookController.resolveIsGroup(data));
  }

  @Test
  void resolveIsGroupDoesNotTreatAnyPhoneGuidAsGroup() {
    BlueBubblesMessageReceivedRequestData data = webhookData("any;+;+18035551212");

    assertFalse(BluebubblesWebhookController.resolveIsGroup(data));
  }

  @Test
  void resolveIsGroupDoesNotTreatAnyEmailGuidAsGroup() {
    BlueBubblesMessageReceivedRequestData data = webhookData("any;+;mindstorms6+apple@gmail.com");

    assertFalse(BluebubblesWebhookController.resolveIsGroup(data));
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

  private static BlueBubblesMessageReceivedRequestData webhookData(String chatGuid) {
    BlueBubblesMessageReceivedRequestDataChatsInner chat =
        new BlueBubblesMessageReceivedRequestDataChatsInner();
    chat.setGuid(chatGuid);

    BlueBubblesMessageReceivedRequestData data = new BlueBubblesMessageReceivedRequestData();
    data.setChats(List.of(chat));
    return data;
  }

  private static ApiV1ChatChatGuidMessageGet200ResponseDataInner historyData(String chatGuid) {
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner();
    chat.setGuid(chatGuid);

    ApiV1ChatChatGuidMessageGet200ResponseDataInner data =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInner();
    data.setChats(List.of(chat));
    return data;
  }
}
