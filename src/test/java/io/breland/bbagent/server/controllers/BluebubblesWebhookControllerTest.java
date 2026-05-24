package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import java.util.List;
import org.junit.jupiter.api.Test;

class BluebubblesWebhookControllerTest {

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
